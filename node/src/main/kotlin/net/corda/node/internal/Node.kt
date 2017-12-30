package net.corda.node.internal

import com.codahale.metrics.JmxReporter
import net.corda.core.concurrent.CordaFuture
import net.corda.core.internal.concurrent.openFuture
import net.corda.core.internal.concurrent.thenMatch
import net.corda.core.internal.uncheckedCast
import net.corda.core.messaging.RPCOps
import net.corda.core.node.NodeInfo
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.IdentityService
import net.corda.core.node.services.TransactionVerifierService
import net.corda.core.serialization.internal.SerializationEnvironmentImpl
import net.corda.core.serialization.internal.nodeSerializationEnv
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.contextLogger
import net.corda.node.VersionInfo
import net.corda.node.internal.cordapp.CordappLoader
import net.corda.node.internal.security.RPCSecurityManagerImpl
import net.corda.node.serialization.KryoServerSerializationScheme
import net.corda.node.services.api.SchemaService
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.config.SecurityConfiguration
import net.corda.node.services.config.VerifierType
import net.corda.node.services.messaging.*
import net.corda.node.services.transactions.InMemoryTransactionVerifierService
import net.corda.node.utilities.AddressUtils
import net.corda.node.utilities.AffinityExecutor
import net.corda.node.utilities.DemoClock
import net.corda.nodeapi.internal.ShutdownHook
import net.corda.nodeapi.internal.addShutdownHook
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.serialization.*
import net.corda.nodeapi.internal.serialization.amqp.AMQPServerSerializationScheme
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rx.Scheduler
import rx.schedulers.Schedulers
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import javax.management.ObjectName
import kotlin.system.exitProcess

/**
 * A Node manages a standalone server that takes part in the P2P network. It creates the services found in [ServiceHub],
 * loads important data off disk and starts listening for connections.
 *
 * @param configuration This is typically loaded from a TypeSafe HOCON configuration file.
 */
open class Node(configuration: NodeConfiguration,
                versionInfo: VersionInfo,
                private val initialiseSerialization: Boolean = true,
                cordappLoader: CordappLoader = makeCordappLoader(configuration)
) : AbstractNode(configuration, createClock(configuration), versionInfo, cordappLoader) {
    companion object {
        private val staticLog = contextLogger()
        var renderBasicInfoToConsole = true

        /** Used for useful info that we always want to show, even when not logging to the console */
        fun printBasicNodeInfo(description: String, info: String? = null) {
            val msg = if (info == null) description else "${description.padEnd(40)}: $info"
            val loggerName = if (renderBasicInfoToConsole) "BasicInfo" else "Main"
            LoggerFactory.getLogger(loggerName).info(msg)
        }

        internal fun failStartUp(message: String): Nothing {
            println(message)
            println("Corda will now exit...")
            exitProcess(1)
        }

        private fun createClock(configuration: NodeConfiguration): Clock {
            return (if (configuration.useTestClock) ::DemoClock else ::SimpleClock)(Clock.systemUTC())
        }

        private val sameVmNodeCounter = AtomicInteger()
        val scanPackagesSystemProperty = "net.corda.node.cordapp.scan.packages"
        val scanPackagesSeparator = ","
        private fun makeCordappLoader(configuration: NodeConfiguration): CordappLoader {
            return System.getProperty(scanPackagesSystemProperty)?.let { scanPackages ->
                CordappLoader.createDefaultWithTestPackages(configuration, scanPackages.split(scanPackagesSeparator))
            } ?: CordappLoader.createDefault(configuration.baseDirectory)
        }
    }

    override val log: Logger get() = staticLog
    override fun makeTransactionVerifierService(): TransactionVerifierService = when (configuration.verifierType) {
        VerifierType.OutOfProcess -> verifierMessagingClient!!.verifierService
        VerifierType.InMemory -> InMemoryTransactionVerifierService(numberOfWorkers = 4)
    }

    private val sameVmNodeNumber = sameVmNodeCounter.incrementAndGet() // Under normal (non-test execution) it will always be "1"

    // DISCUSSION
    //
    // We use a single server thread for now, which means all message handling is serialized.
    //
    // Writing thread safe code is hard. In this project we are writing most node services and code to be thread safe, but
    // the possibility of mistakes is always present. Thus we make a deliberate decision here to trade off some multi-core
    // scalability in order to gain developer productivity by setting the size of the serverThread pool to one, which will
    // reduce the number of threading bugs we will need to tackle.
    //
    // This leaves us with four possibilities in future:
    //
    // (1) We discover that processing messages is fast and that our eventual use cases do not need very high
    //     processing rates. We have benefited from the higher productivity and not lost anything.
    //
    // (2) We discover that we need greater multi-core scalability, but that the bulk of our time goes into particular CPU
    //     hotspots that are easily multi-threaded e.g. signature checking. We successfully multi-thread those hotspots
    //     and find that our software now scales sufficiently well to satisfy our user's needs.
    //
    // (3) We discover that it wasn't enough, but that we only need to run some messages in parallel and that the bulk of
    //     the work can stay single threaded. For example perhaps we find that latency sensitive UI requests must be handled
    //     on a separate thread pool where long blocking operations are not allowed, but that the bulk of the heavy lifting
    //     can stay single threaded. In this case we would need a separate thread pool, but we still minimise the amount of
    //     thread safe code we need to write and test.
    //
    // (4) None of the above are sufficient and we need to run all messages in parallel to get maximum (single machine)
    //     scalability and fully saturate all cores. In that case we can go fully free-threaded, e.g. change the number '1'
    //     below to some multiple of the core count. Alternatively by using the ForkJoinPool and let it figure out the right
    //     number of threads by itself. This will require some investment in stress testing to build confidence that we
    //     haven't made any mistakes, but it will only be necessary if eventual deployment scenarios demand it.
    //
    // Note that the messaging subsystem schedules work onto this thread in a blocking manner. That means if the server
    // thread becomes too slow and a backlog of work starts to builds up it propagates back through into the messaging
    // layer, which can then react to the backpressure. Artemis MQ in particular knows how to do flow control by paging
    // messages to disk rather than letting us run out of RAM.
    //
    // The primary work done by the server thread is execution of flow logics, and related
    // serialisation/deserialisation work.
    override val serverThread = AffinityExecutor.ServiceAffinityExecutor("Node thread-$sameVmNodeNumber", 1)

    private var messageBroker: ArtemisMessagingServer? = null

    private var shutdownHook: ShutdownHook? = null

    override fun makeMessagingService(database: CordaPersistence, info: NodeInfo): MessagingService {
        // Construct security manager reading users data either from the 'security' config section
        // if present or from rpcUsers list if the former is missing from config.
        val securityManagerConfig = configuration.security?.authService ?:
            SecurityConfiguration.AuthService.fromUsers(configuration.rpcUsers)

        securityManager = RPCSecurityManagerImpl(securityManagerConfig)

        val serverAddress = configuration.messagingServerAddress ?: makeLocalMessageBroker()
        val advertisedAddress = info.addresses.single()

        printBasicNodeInfo("Incoming connection address", advertisedAddress.toString())
        rpcMessagingClient = RPCMessagingClient(configuration, serverAddress, networkParameters.maxMessageSize)
        verifierMessagingClient = when (configuration.verifierType) {
            VerifierType.OutOfProcess -> VerifierMessagingClient(configuration, serverAddress, services.monitoringService.metrics, networkParameters.maxMessageSize)
            VerifierType.InMemory -> null
        }
        return P2PMessagingClient(
                configuration,
                versionInfo,
                serverAddress,
                info.legalIdentities[0].owningKey,
                serverThread,
                database,
                advertisedAddress,
                networkParameters.maxMessageSize)
    }

    private fun makeLocalMessageBroker(): NetworkHostAndPort {
        with(configuration) {
            messageBroker = ArtemisMessagingServer(this, p2pAddress.port, rpcAddress?.port, services.networkMapCache, securityManager, networkParameters.maxMessageSize)
            return NetworkHostAndPort("localhost", p2pAddress.port)
        }
    }

    override fun myAddresses(): List<NetworkHostAndPort> {
        return listOf(configuration.messagingServerAddress ?: getAdvertisedAddress())
    }

    private fun getAdvertisedAddress(): NetworkHostAndPort {
        return with(configuration) {
            val host = if (detectPublicIp) {
                tryDetectIfNotPublicHost(p2pAddress.host) ?: p2pAddress.host
            } else {
                p2pAddress.host
            }
            NetworkHostAndPort(host, p2pAddress.port)
        }
    }

    /**
     * Checks whether the specified [host] is a public IP address or hostname. If not, tries to discover the current
     * machine's public IP address to be used instead by looking through the network interfaces.
     * TODO this code used to rely on the networkmap node, we might want to look at a different solution.
     */
    private fun tryDetectIfNotPublicHost(host: String): String? {
        return if (!AddressUtils.isPublic(host)) {
            val foundPublicIP = AddressUtils.tryDetectPublicIP()
            if (foundPublicIP == null) {
                try {
                    val retrievedHostName = networkMapClient?.myPublicHostname()
                    if (retrievedHostName != null) {
                        log.info("Retrieved public IP from Network Map Service: $this. This will be used instead of the provided \"$host\" as the advertised address.")
                    }
                    retrievedHostName
                } catch (ignore: Throwable) {
                    // Cannot reach the network map service, ignore the exception and use provided P2P address instead.
                    log.warn("Cannot connect to the network map service for public IP detection.")
                    null
                }
            } else {
                log.info("Detected public IP: ${foundPublicIP.hostAddress}. This will be used instead of the provided \"$host\" as the advertised address.")
                foundPublicIP.hostAddress
            }
        } else {
            null
        }
    }

    override fun startMessagingService(rpcOps: RPCOps) {
        // Start up the embedded MQ server
        messageBroker?.apply {
            runOnStop += this::stop
            start()
        }
        // Start up the MQ clients.
        rpcMessagingClient.run {
            runOnStop += this::stop
            start(rpcOps, securityManager)
        }
        verifierMessagingClient?.run {
            runOnStop += this::stop
            start()
        }
        (network as P2PMessagingClient).apply {
            runOnStop += this::stop
            start()
        }
    }

    /**
     * If the node is persisting to an embedded H2 database, then expose this via TCP with a DB URL of the form:
     * jdbc:h2:tcp://<host>:<port>/node
     * with username and password as per the DataSource connection details.  The key element to enabling this support is to
     * ensure that you specify a DB connection URL of the form jdbc:h2:file: in the node config and that you include
     * the H2 option AUTO_SERVER_PORT set to the port you desire to use (0 will give a dynamically allocated port number)
     * but exclude the H2 option AUTO_SERVER=TRUE.
     * This is not using the H2 "automatic mixed mode" directly but leans on many of the underpinnings.  For more details
     * on H2 URLs and configuration see: http://www.h2database.com/html/features.html#database_url
     */
    override fun <T> initialiseDatabasePersistence(schemaService: SchemaService, identityService: IdentityService, insideTransaction: (CordaPersistence) -> T): T {
        val databaseUrl = configuration.dataSourceProperties.getProperty("dataSource.url")
        val h2Prefix = "jdbc:h2:file:"
        if (databaseUrl != null && databaseUrl.startsWith(h2Prefix)) {
            val h2Port = databaseUrl.substringAfter(";AUTO_SERVER_PORT=", "").substringBefore(';')
            if (h2Port.isNotBlank()) {
                val databaseName = databaseUrl.removePrefix(h2Prefix).substringBefore(';')
                val server = org.h2.tools.Server.createTcpServer(
                        "-tcpPort", h2Port,
                        "-tcpAllowOthers",
                        "-tcpDaemon",
                        "-key", "node", databaseName)
                runOnStop += server::stop
                val url = server.start().url
                printBasicNodeInfo("Database connection url is", "jdbc:h2:$url/node")
            }
        }
        return super.initialiseDatabasePersistence(schemaService, identityService, insideTransaction)
    }

    private val _startupComplete = openFuture<Unit>()
    val startupComplete: CordaFuture<Unit> get() = _startupComplete

    override fun generateNodeInfo(): NodeInfo {
        initialiseSerialization()
        return super.generateNodeInfo()
    }

    override fun start(): StartedNode<Node> {
        initialiseSerialization()
        val started: StartedNode<Node> = uncheckedCast(super.start())
        nodeReadyFuture.thenMatch({
            serverThread.execute {
                // Begin exporting our own metrics via JMX. These can be monitored using any agent, e.g. Jolokia:
                //
                // https://jolokia.org/agent/jvm.html
                JmxReporter.
                        forRegistry(started.services.monitoringService.metrics).
                        inDomain("net.corda").
                        createsObjectNamesWith { _, domain, name ->
                            // Make the JMX hierarchy a bit better organised.
                            val category = name.substringBefore('.')
                            val subName = name.substringAfter('.', "")
                            if (subName == "")
                                ObjectName("$domain:name=$category")
                            else
                                ObjectName("$domain:type=$category,name=$subName")
                        }.
                        build().
                        start()

                _startupComplete.set(Unit)
            }
        },
                { th -> staticLog.error("Unexpected exception", th) } // XXX: Why not use log?
        )
        shutdownHook = addShutdownHook {
            stop()
        }
        return started
    }

    override fun getRxIoScheduler(): Scheduler = Schedulers.io()

    private fun initialiseSerialization() {
        if (!initialiseSerialization) return
        val classloader = cordappLoader.appClassLoader
        nodeSerializationEnv = SerializationEnvironmentImpl(
                SerializationFactoryImpl().apply {
                    registerScheme(KryoServerSerializationScheme())
                    registerScheme(AMQPServerSerializationScheme(cordappLoader.cordapps))
                },
                p2pContext = AMQP_P2P_CONTEXT.withClassLoader(classloader),
                rpcServerContext = KRYO_RPC_SERVER_CONTEXT.withClassLoader(classloader),
                storageContext = AMQP_STORAGE_CONTEXT.withClassLoader(classloader),
                checkpointContext = KRYO_CHECKPOINT_CONTEXT.withClassLoader(classloader))
    }

    private lateinit var rpcMessagingClient: RPCMessagingClient
    private var verifierMessagingClient: VerifierMessagingClient? = null
    /** Starts a blocking event loop for message dispatch. */
    fun run() {
        rpcMessagingClient.start2(messageBroker!!.serverControl)
        verifierMessagingClient?.start2()
        (network as P2PMessagingClient).run()
    }

    private var shutdown = false

    override fun stop() {
        check(!serverThread.isOnThread)
        synchronized(this) {
            if (shutdown) return
            shutdown = true
            // Unregister shutdown hook to prevent any unnecessary second calls to stop
            shutdownHook?.cancel()
            shutdownHook = null
        }
        printBasicNodeInfo("Shutting down ...")

        // All the Node started subsystems were registered with the runOnStop list at creation.
        // So now simply call the parent to stop everything in reverse order.
        // In particular this prevents premature shutdown of the Database by AbstractNode whilst the serverThread is active
        super.stop()

        log.info("Shutdown complete")
    }
}

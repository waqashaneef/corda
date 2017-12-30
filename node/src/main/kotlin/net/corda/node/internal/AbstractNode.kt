package net.corda.node.internal

import com.codahale.metrics.MetricRegistry
import com.google.common.collect.MutableClassToInstanceMap
import com.google.common.util.concurrent.MoreExecutors
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.corda.confidential.SwapIdentitiesFlow
import net.corda.confidential.SwapIdentitiesHandler
import net.corda.core.CordaException
import net.corda.core.concurrent.CordaFuture
import net.corda.core.context.InvocationContext
import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.DigitalSignature
import net.corda.core.crypto.SignedData
import net.corda.core.crypto.sign
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.internal.*
import net.corda.core.internal.concurrent.map
import net.corda.core.internal.concurrent.openFuture
import net.corda.core.messaging.*
import net.corda.core.node.*
import net.corda.core.node.services.*
import net.corda.core.serialization.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.debug
import net.corda.core.utilities.getOrThrow
import net.corda.node.VersionInfo
import net.corda.node.internal.classloading.requireAnnotation
import net.corda.node.internal.cordapp.CordappLoader
import net.corda.node.internal.cordapp.CordappProviderImpl
import net.corda.node.internal.cordapp.CordappProviderInternal
import net.corda.node.internal.security.RPCSecurityManager
import net.corda.node.services.ContractUpgradeHandler
import net.corda.node.services.FinalityHandler
import net.corda.node.services.NotaryChangeHandler
import net.corda.node.services.api.*
import net.corda.node.services.config.BFTSMaRtConfiguration
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.config.NotaryConfig
import net.corda.node.services.config.configureWithDevSSLCertificate
import net.corda.node.services.events.NodeSchedulerService
import net.corda.node.services.events.ScheduledActivityObserver
import net.corda.node.services.identity.PersistentIdentityService
import net.corda.node.services.keys.PersistentKeyManagementService
import net.corda.node.services.messaging.MessagingService
import net.corda.node.services.network.*
import net.corda.node.services.persistence.*
import net.corda.node.services.schema.HibernateObserver
import net.corda.node.services.schema.NodeSchemaService
import net.corda.node.services.statemachine.*
import net.corda.node.services.transactions.*
import net.corda.node.services.upgrade.ContractUpgradeServiceImpl
import net.corda.node.services.vault.NodeVaultService
import net.corda.node.services.vault.VaultSoftLockManager
import net.corda.node.shell.InteractiveShell
import net.corda.node.utilities.AffinityExecutor
import net.corda.nodeapi.internal.DevIdentityGenerator
import net.corda.nodeapi.internal.SignedNodeInfo
import net.corda.nodeapi.internal.crypto.KeyStoreWrapper
import net.corda.nodeapi.internal.crypto.X509CertificateFactory
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.crypto.loadKeyStore
import net.corda.nodeapi.internal.network.NETWORK_PARAMS_FILE_NAME
import net.corda.nodeapi.internal.network.NetworkParameters
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.nodeapi.internal.persistence.HibernateConfiguration
import org.apache.activemq.artemis.utils.ReusableLatch
import org.hibernate.type.descriptor.java.JavaTypeDescriptorRegistry
import org.slf4j.Logger
import rx.Observable
import rx.Scheduler
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.security.KeyPair
import java.security.KeyStoreException
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.collections.set
import kotlin.reflect.KClass
import net.corda.core.crypto.generateKeyPair as cryptoGenerateKeyPair

/**
 * A base node implementation that can be customised either for production (with real implementations that do real
 * I/O), or a mock implementation suitable for unit test environments.
 *
 * Marked as SingletonSerializeAsToken to prevent the invisible reference to AbstractNode in the ServiceHub accidentally
 * sweeping up the Node into the Kryo checkpoint serialization via any flows holding a reference to ServiceHub.
 */
// TODO Log warning if this node is a notary but not one of the ones specified in the network parameters, both for core and custom

// In theory the NodeInfo for the node should be passed in, instead, however currently this is constructed by the
// AbstractNode. It should be possible to generate the NodeInfo outside of AbstractNode, so it can be passed in.
abstract class AbstractNode(val configuration: NodeConfiguration,
                            val platformClock: Clock,
                            protected val versionInfo: VersionInfo,
                            protected val cordappLoader: CordappLoader,
                            private val busyNodeLatch: ReusableLatch = ReusableLatch()) : SingletonSerializeAsToken() {

    private class StartedNodeImpl<out N : AbstractNode>(
            override val internals: N,
            services: ServiceHubInternalImpl,
            override val info: NodeInfo,
            override val checkpointStorage: CheckpointStorage,
            override val smm: StateMachineManager,
            override val attachments: NodeAttachmentService,
            override val network: MessagingService,
            override val database: CordaPersistence,
            override val rpcOps: CordaRPCOps,
            flowStarter: FlowStarter,
            override val notaryService: NotaryService?) : StartedNode<N> {
        override val services: StartedNodeServices = object : StartedNodeServices, ServiceHubInternal by services, FlowStarter by flowStarter {}
    }

    protected abstract val log: Logger

    // We will run as much stuff in this single thread as possible to keep the risk of thread safety bugs low during the
    // low-performance prototyping period.
    protected abstract val serverThread: AffinityExecutor

    protected lateinit var networkParameters: NetworkParameters
    private val cordappServices = MutableClassToInstanceMap.create<SerializeAsToken>()
    private val flowFactories = ConcurrentHashMap<Class<out FlowLogic<*>>, InitiatedFlowFactory<*>>()

    protected val services: ServiceHubInternal get() = _services
    private lateinit var _services: ServiceHubInternalImpl
    protected var myNotaryIdentity: PartyAndCertificate? = null
    private lateinit var checkpointStorage: CheckpointStorage
    private lateinit var tokenizableServices: List<Any>
    protected lateinit var attachments: NodeAttachmentService
    protected lateinit var network: MessagingService
    protected val runOnStop = ArrayList<() -> Any?>()
    private val _nodeReadyFuture = openFuture<Unit>()
    protected var networkMapClient: NetworkMapClient? = null

    lateinit var securityManager: RPCSecurityManager get

    /** Completes once the node has successfully registered with the network map service
     * or has loaded network map data from local database */
    val nodeReadyFuture: CordaFuture<Unit> get() = _nodeReadyFuture

    open val serializationWhitelists: List<SerializationWhitelist> by lazy {
        cordappLoader.cordapps.flatMap { it.serializationWhitelists }
    }

    /** Set to non-null once [start] has been successfully called. */
    open val started get() = _started
    @Volatile private var _started: StartedNode<AbstractNode>? = null

    /** The implementation of the [CordaRPCOps] interface used by this node. */
    open fun makeRPCOps(flowStarter: FlowStarter, database: CordaPersistence, smm: StateMachineManager): CordaRPCOps {
        return SecureCordaRPCOps(services, smm, database, flowStarter)
    }

    private fun initCertificate() {
        if (configuration.devMode) {
            log.warn("Corda node is running in dev mode.")
            configuration.configureWithDevSSLCertificate()
        }
        validateKeystore()
    }

    private inline fun signNodeInfo(nodeInfo: NodeInfo, sign: (PublicKey, SerializedBytes<NodeInfo>) -> DigitalSignature): SignedNodeInfo {
        // For now we assume the node has only one identity (excluding any composite ones)
        val owningKey = nodeInfo.legalIdentities.single { it.owningKey !is CompositeKey }.owningKey
        val serialised = nodeInfo.serialize()
        val signature = sign(owningKey, serialised)
        return SignedNodeInfo(serialised, listOf(signature))
    }

    open fun generateNodeInfo(): NodeInfo {
        check(started == null) { "Node has already been started" }
        log.info("Generating nodeInfo ...")
        initCertificate()
        val schemaService = NodeSchemaService(cordappLoader.cordappSchemas)
        val (identity, identityKeyPair) = obtainIdentity(notaryConfig = null)
        return initialiseDatabasePersistence(schemaService,  makeIdentityService(identity.certificate)) { database ->
            // TODO The fact that we need to specify an empty list of notaries just to generate our node info looks like
            // a code smell.
            val persistentNetworkMapCache = PersistentNetworkMapCache(database, notaries = emptyList())
            val (keyPairs, info) = initNodeInfo(persistentNetworkMapCache, identity, identityKeyPair)
            val signedNodeInfo = signNodeInfo(info) { publicKey, serialised ->
                val privateKey = keyPairs.single { it.public == publicKey }.private
                privateKey.sign(serialised.bytes)
            }
            NodeInfoWatcher.saveToFile(configuration.baseDirectory, signedNodeInfo)
            info
        }
    }

    open fun start(): StartedNode<AbstractNode> {
        check(started == null) { "Node has already been started" }
        log.info("Node starting up ...")
        initCertificate()
        val schemaService = NodeSchemaService(cordappLoader.cordappSchemas)
        val (identity, identityKeyPair) = obtainIdentity(notaryConfig = null)
        val identityService = makeIdentityService(identity.certificate)
        networkMapClient = configuration.compatibilityZoneURL?.let { NetworkMapClient(it, identityService.trustRoot) }
        retrieveNetworkParameters()
        // Do all of this in a database transaction so anything that might need a connection has one.
        val (startedImpl, schedulerService) = initialiseDatabasePersistence(schemaService, identityService) { database ->
            val networkMapCache = NetworkMapCacheImpl(PersistentNetworkMapCache(database, networkParameters.notaries), identityService)
            val (keyPairs, info) = initNodeInfo(networkMapCache, identity, identityKeyPair)
            identityService.loadIdentities(info.legalIdentitiesAndCerts)
            val transactionStorage = makeTransactionStorage(database)
            val nodeServices = makeServices(keyPairs, schemaService, transactionStorage, database, info, identityService, networkMapCache)
            val notaryService = makeNotaryService(nodeServices, database)
            val smm = makeStateMachineManager(database)
            val flowStarter = FlowStarterImpl(serverThread, smm)
            val schedulerService = NodeSchedulerService(
                    platformClock,
                    database,
                    flowStarter,
                    transactionStorage,
                    unfinishedSchedules = busyNodeLatch,
                    serverThread = serverThread)
            if (serverThread is ExecutorService) {
                runOnStop += {
                    // We wait here, even though any in-flight messages should have been drained away because the
                    // server thread can potentially have other non-messaging tasks scheduled onto it. The timeout value is
                    // arbitrary and might be inappropriate.
                    MoreExecutors.shutdownAndAwaitTermination(serverThread as ExecutorService, 50, SECONDS)
                }
            }
            makeVaultObservers(schedulerService, database.hibernateConfig, smm, schemaService)
            val rpcOps = makeRPCOps(flowStarter, database, smm)
            startMessagingService(rpcOps)
            installCoreFlows()
            val cordaServices = installCordaServices(flowStarter)
            tokenizableServices = nodeServices + cordaServices + schedulerService
            registerCordappFlows(smm)
            _services.rpcFlows += cordappLoader.cordapps.flatMap { it.rpcFlows }
            FlowLogicRefFactoryImpl.classloader = cordappLoader.appClassLoader
            startShell(rpcOps)
            Pair(StartedNodeImpl(this, _services, info, checkpointStorage, smm, attachments, network, database, rpcOps, flowStarter, notaryService), schedulerService)
        }
        val networkMapUpdater = NetworkMapUpdater(services.networkMapCache,
                NodeInfoWatcher(configuration.baseDirectory, getRxIoScheduler(), Duration.ofMillis(configuration.additionalNodeInfoPollingFrequencyMsec)),
                networkMapClient,
                networkParameters.serialize().hash)
        runOnStop += networkMapUpdater::close

        networkMapUpdater.updateNodeInfo(services.myInfo) {
            signNodeInfo(it) { publicKey, serialised ->
                services.keyManagementService.sign(serialised.bytes, publicKey).withoutKey()
            }
        }
        networkMapUpdater.subscribeToNetworkMap()

        // If we successfully  loaded network data from database, we set this future to Unit.
        _nodeReadyFuture.captureLater(services.networkMapCache.nodeReady.map { Unit })

        return startedImpl.apply {
            database.transaction {
                smm.start(tokenizableServices)
                // Shut down the SMM so no Fibers are scheduled.
                runOnStop += { smm.stop(acceptableLiveFiberCountOnStop()) }
                schedulerService.start()
            }
            _started = this
        }
    }

    /**
     * Should be [rx.schedulers.Schedulers.io] for production,
     * or [rx.internal.schedulers.CachedThreadScheduler] (with shutdown registered with [runOnStop]) for shared-JVM testing.
     */
    protected abstract fun getRxIoScheduler(): Scheduler

    open fun startShell(rpcOps: CordaRPCOps) {
        InteractiveShell.startShell(configuration, rpcOps, securityManager, _services.identityService, _services.database)
    }

    private fun initNodeInfo(networkMapCache: NetworkMapCacheBaseInternal,
                             identity: PartyAndCertificate,
                             identityKeyPair: KeyPair): Pair<Set<KeyPair>, NodeInfo> {
        val keyPairs = mutableSetOf(identityKeyPair)

        myNotaryIdentity = configuration.notary?.let {
            if (it.isClusterConfig) {
                val (notaryIdentity, notaryIdentityKeyPair) = obtainIdentity(it)
                keyPairs += notaryIdentityKeyPair
                notaryIdentity
            } else {
                // In case of a single notary service myNotaryIdentity will be the node's single identity.
                identity
            }
        }

        var info = NodeInfo(
                myAddresses(),
                setOf(identity, myNotaryIdentity).filterNotNull(),
                versionInfo.platformVersion,
                platformClock.instant().toEpochMilli()
        )
        // Check if we have already stored a version of 'our own' NodeInfo, this is to avoid regenerating it with
        // a different timestamp.
        networkMapCache.getNodesByLegalName(configuration.myLegalName).firstOrNull()?.let {
            if (info.copy(serial = it.serial) == it) {
                info = it
            }
        }
        return Pair(keyPairs, info)
    }

    protected abstract fun myAddresses(): List<NetworkHostAndPort>
    protected open fun makeStateMachineManager(database: CordaPersistence): StateMachineManager {
        return StateMachineManagerImpl(
                services,
                checkpointStorage,
                serverThread,
                database,
                busyNodeLatch,
                cordappLoader.appClassLoader
        )
    }

    private class ServiceInstantiationException(cause: Throwable?) : CordaException("Service Instantiation Error", cause)

    private fun installCordaServices(flowStarter: FlowStarter): List<SerializeAsToken> {
        val loadedServices = cordappLoader.cordapps.flatMap { it.services }
        return filterServicesToInstall(loadedServices).mapNotNull {
            try {
                installCordaService(flowStarter, it)
            } catch (e: NoSuchMethodException) {
                log.error("${it.name}, as a Corda service, must have a constructor with a single parameter of type " +
                        ServiceHub::class.java.name)
                null
            } catch (e: ServiceInstantiationException) {
                log.error("Corda service ${it.name} failed to instantiate", e.cause)
                null
            } catch (e: Exception) {
                log.error("Unable to install Corda service ${it.name}", e)
                null
            }
        }
    }

    private fun filterServicesToInstall(loadedServices: List<Class<out SerializeAsToken>>): List<Class<out SerializeAsToken>> {
        val customNotaryServiceList = loadedServices.filter { isNotaryService(it) }
        if (customNotaryServiceList.isNotEmpty()) {
            if (configuration.notary?.custom == true) {
                require(customNotaryServiceList.size == 1) {
                    "Attempting to install more than one notary service: ${customNotaryServiceList.joinToString()}"
                }
            } else return loadedServices - customNotaryServiceList
        }
        return loadedServices
    }

    /**
     * If the [serviceClass] is a notary service, it will only be enable if the "custom" flag is set in
     * the notary configuration.
     */
    private fun isNotaryService(serviceClass: Class<*>) = NotaryService::class.java.isAssignableFrom(serviceClass)

    /**
     * This customizes the ServiceHub for each CordaService that is initiating flows
     */
    private class AppServiceHubImpl<T : SerializeAsToken>(private val serviceHub: ServiceHub, private val flowStarter: FlowStarter) : AppServiceHub, ServiceHub by serviceHub {
        lateinit var serviceInstance: T
        override fun <T> startTrackedFlow(flow: FlowLogic<T>): FlowProgressHandle<T> {
            val stateMachine = startFlowChecked(flow)
            return FlowProgressHandleImpl(
                    id = stateMachine.id,
                    returnValue = stateMachine.resultFuture,
                    progress = stateMachine.logic.track()?.updates ?: Observable.empty()
            )
        }

        override fun <T> startFlow(flow: FlowLogic<T>): FlowHandle<T> {
            val stateMachine = startFlowChecked(flow)
            return FlowHandleImpl(id = stateMachine.id, returnValue = stateMachine.resultFuture)
        }

        private fun <T> startFlowChecked(flow: FlowLogic<T>): FlowStateMachine<T> {
            val logicType = flow.javaClass
            require(logicType.isAnnotationPresent(StartableByService::class.java)) { "${logicType.name} was not designed for starting by a CordaService" }
            // TODO check service permissions
            // TODO switch from myInfo.legalIdentities[0].name to current node's identity as soon as available
            val context = InvocationContext.service(serviceInstance.javaClass.name, myInfo.legalIdentities[0].name)
            return flowStarter.startFlow(flow, context).getOrThrow()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AppServiceHubImpl<*>) return false
            return serviceHub == other.serviceHub
                    && flowStarter == other.flowStarter
                    && serviceInstance == other.serviceInstance
        }

        override fun hashCode() = Objects.hash(serviceHub, flowStarter, serviceInstance)
    }

    private fun <T : SerializeAsToken> installCordaService(flowStarter: FlowStarter, serviceClass: Class<T>): T {
        serviceClass.requireAnnotation<CordaService>()
        val service = try {
            val serviceContext = AppServiceHubImpl<T>(services, flowStarter)
            if (isNotaryService(serviceClass)) {
                check(myNotaryIdentity != null) { "Trying to install a notary service but no notary identity specified" }
                val constructor = serviceClass.getDeclaredConstructor(AppServiceHub::class.java, PublicKey::class.java).apply { isAccessible = true }
                serviceContext.serviceInstance = constructor.newInstance(serviceContext, myNotaryIdentity!!.owningKey)
                serviceContext.serviceInstance
            } else {
                try {
                    val extendedServiceConstructor = serviceClass.getDeclaredConstructor(AppServiceHub::class.java).apply { isAccessible = true }
                    serviceContext.serviceInstance = extendedServiceConstructor.newInstance(serviceContext)
                    serviceContext.serviceInstance
                } catch (ex: NoSuchMethodException) {
                    val constructor = serviceClass.getDeclaredConstructor(ServiceHub::class.java).apply { isAccessible = true }
                    log.warn("${serviceClass.name} is using legacy CordaService constructor with ServiceHub parameter. Upgrade to an AppServiceHub parameter to enable updated API features.")
                    constructor.newInstance(services)
                }
            }
        } catch (e: InvocationTargetException) {
            throw ServiceInstantiationException(e.cause)
        }
        cordappServices.putInstance(serviceClass, service)

        if (service is NotaryService) handleCustomNotaryService(service)

        log.info("Installed ${serviceClass.name} Corda service")
        return service
    }

    private fun handleCustomNotaryService(service: NotaryService) {
        runOnStop += service::stop
        service.start()
        installCoreFlow(NotaryFlow.Client::class, service::createServiceFlow)
    }

    private fun registerCordappFlows(smm: StateMachineManager) {
        cordappLoader.cordapps.flatMap { it.initiatedFlows }
                .forEach {
                    try {
                        registerInitiatedFlowInternal(smm, it, track = false)
                    } catch (e: NoSuchMethodException) {
                        log.error("${it.name}, as an initiated flow, must have a constructor with a single parameter " +
                                "of type ${Party::class.java.name}")
                    } catch (e: Exception) {
                        log.error("Unable to register initiated flow ${it.name}", e)
                    }
                }
    }

    internal fun <T : FlowLogic<*>> registerInitiatedFlow(smm: StateMachineManager, initiatedFlowClass: Class<T>): Observable<T> {
        return registerInitiatedFlowInternal(smm, initiatedFlowClass, track = true)
    }

    // TODO remove once not needed
    private fun deprecatedFlowConstructorMessage(flowClass: Class<*>): String {
        return "Installing flow factory for $flowClass accepting a ${Party::class.java.simpleName}, which is deprecated. " +
                "It should accept a ${FlowSession::class.java.simpleName} instead"
    }

    private fun <F : FlowLogic<*>> registerInitiatedFlowInternal(smm: StateMachineManager, initiatedFlow: Class<F>, track: Boolean): Observable<F> {
        val constructors = initiatedFlow.declaredConstructors.associateBy { it.parameterTypes.toList() }
        val flowSessionCtor = constructors[listOf(FlowSession::class.java)]?.apply { isAccessible = true }
        val ctor: (FlowSession) -> F = if (flowSessionCtor == null) {
            // Try to fallback to a Party constructor
            val partyCtor = constructors[listOf(Party::class.java)]?.apply { isAccessible = true }
            if (partyCtor == null) {
                throw IllegalArgumentException("$initiatedFlow must have a constructor accepting a ${FlowSession::class.java.name}")
            } else {
                log.warn(deprecatedFlowConstructorMessage(initiatedFlow))
            }
            { flowSession: FlowSession -> uncheckedCast(partyCtor.newInstance(flowSession.counterparty)) }
        } else {
            { flowSession: FlowSession -> uncheckedCast(flowSessionCtor.newInstance(flowSession)) }
        }
        val initiatingFlow = initiatedFlow.requireAnnotation<InitiatedBy>().value.java
        val (version, classWithAnnotation) = initiatingFlow.flowVersionAndInitiatingClass
        require(classWithAnnotation == initiatingFlow) {
            "${InitiatedBy::class.java.name} must point to ${classWithAnnotation.name} and not ${initiatingFlow.name}"
        }
        val flowFactory = InitiatedFlowFactory.CorDapp(version, initiatedFlow.appName, ctor)
        val observable = internalRegisterFlowFactory(smm, initiatingFlow, flowFactory, initiatedFlow, track)
        log.info("Registered ${initiatingFlow.name} to initiate ${initiatedFlow.name} (version $version)")
        return observable
    }

    internal fun <F : FlowLogic<*>> internalRegisterFlowFactory(smm: StateMachineManager,
                                                                initiatingFlowClass: Class<out FlowLogic<*>>,
                                                                flowFactory: InitiatedFlowFactory<F>,
                                                                initiatedFlowClass: Class<F>,
                                                                track: Boolean): Observable<F> {
        val observable = if (track) {
            smm.changes.filter { it is StateMachineManager.Change.Add }.map { it.logic }.ofType(initiatedFlowClass)
        } else {
            Observable.empty()
        }
        flowFactories[initiatingFlowClass] = flowFactory
        return observable
    }

    /**
     * Installs a flow that's core to the Corda platform. Unlike CorDapp flows which are versioned individually using
     * [InitiatingFlow.version], core flows have the same version as the node's platform version. To cater for backwards
     * compatibility [flowFactory] provides a second parameter which is the platform version of the initiating party.
     * @suppress
     */
    @VisibleForTesting
    fun installCoreFlow(clientFlowClass: KClass<out FlowLogic<*>>, flowFactory: (FlowSession) -> FlowLogic<*>) {
        require(clientFlowClass.java.flowVersionAndInitiatingClass.first == 1) {
            "${InitiatingFlow::class.java.name}.version not applicable for core flows; their version is the node's platform version"
        }
        flowFactories[clientFlowClass.java] = InitiatedFlowFactory.Core(flowFactory)
        log.debug { "Installed core flow ${clientFlowClass.java.name}" }
    }


    private fun installCoreFlows() {
        installCoreFlow(FinalityFlow::class, ::FinalityHandler)
        installCoreFlow(NotaryChangeFlow::class, ::NotaryChangeHandler)
        installCoreFlow(ContractUpgradeFlow.Initiate::class, ::ContractUpgradeHandler)
        installCoreFlow(SwapIdentitiesFlow::class, ::SwapIdentitiesHandler)
    }

    /**
     * Builds node internal, advertised, and plugin services.
     * Returns a list of tokenizable services to be added to the serialisation context.
     */
    private fun makeServices(keyPairs: Set<KeyPair>, schemaService: SchemaService, transactionStorage: WritableTransactionStorage,  database: CordaPersistence, info: NodeInfo, identityService: IdentityServiceInternal, networkMapCache: NetworkMapCacheInternal): MutableList<Any> {
        checkpointStorage = DBCheckpointStorage()
        val metrics = MetricRegistry()
        attachments = NodeAttachmentService(metrics)
        val cordappProvider = CordappProviderImpl(cordappLoader, attachments)
        val keyManagementService = makeKeyManagementService(identityService, keyPairs)
        _services = ServiceHubInternalImpl(
                identityService,
                keyManagementService,
                schemaService,
                transactionStorage,
                MonitoringService(metrics),
                cordappProvider,
                database,
                info,
                networkMapCache)
        network = makeMessagingService(database, info)
        val tokenizableServices = mutableListOf(attachments, network, services.vaultService,
                services.keyManagementService, services.identityService, platformClock,
                services.auditService, services.monitoringService, services.networkMapCache, services.schemaService,
                services.transactionVerifierService, services.validatedTransactions, services.contractUpgradeService,
                services, cordappProvider, this)
        return tokenizableServices
    }

    protected open fun makeTransactionStorage(database: CordaPersistence): WritableTransactionStorage = DBTransactionStorage()

    private fun makeVaultObservers(schedulerService: SchedulerService, hibernateConfig: HibernateConfiguration, smm: StateMachineManager, schemaService: SchemaService) {
        VaultSoftLockManager.install(services.vaultService, smm)
        ScheduledActivityObserver.install(services.vaultService, schedulerService)
        HibernateObserver.install(services.vaultService.rawUpdates, hibernateConfig, schemaService)
    }

    @VisibleForTesting
    protected open fun acceptableLiveFiberCountOnStop(): Int = 0

    private fun validateKeystore() {
        val containCorrectKeys = try {
            // This will throw IOException if key file not found or KeyStoreException if keystore password is incorrect.
            val sslKeystore = loadKeyStore(configuration.sslKeystore, configuration.keyStorePassword)
            val identitiesKeystore = loadKeyStore(configuration.nodeKeystore, configuration.keyStorePassword)
            sslKeystore.containsAlias(X509Utilities.CORDA_CLIENT_TLS) && identitiesKeystore.containsAlias(X509Utilities.CORDA_CLIENT_CA)
        } catch (e: KeyStoreException) {
            log.warn("Certificate key store found but key store password does not match configuration.")
            false
        } catch (e: IOException) {
            false
        }
        require(containCorrectKeys) {
            "Identity certificate not found. " +
                    "Please either copy your existing identity key and certificate from another node, " +
                    "or if you don't have one yet, fill out the config file and run corda.jar --initial-registration. " +
                    "Read more at: https://docs.corda.net/permissioning.html"
        }

        // Check all cert path chain to the trusted root
        val sslKeystore = loadKeyStore(configuration.sslKeystore, configuration.keyStorePassword)
        val identitiesKeystore = loadKeyStore(configuration.nodeKeystore, configuration.keyStorePassword)
        val trustStore = loadKeyStore(configuration.trustStoreFile, configuration.trustStorePassword)
        val sslRoot = sslKeystore.getCertificateChain(X509Utilities.CORDA_CLIENT_TLS).last()
        val clientCARoot = identitiesKeystore.getCertificateChain(X509Utilities.CORDA_CLIENT_CA).last()
        val trustRoot = trustStore.getCertificate(X509Utilities.CORDA_ROOT_CA)

        require(sslRoot == trustRoot) { "TLS certificate must chain to the trusted root." }
        require(clientCARoot == trustRoot) { "Client CA certificate must chain to the trusted root." }
    }

    // Specific class so that MockNode can catch it.
    class DatabaseConfigurationException(msg: String) : CordaException(msg)

    protected open fun <T> initialiseDatabasePersistence(schemaService: SchemaService, identityService: IdentityService, insideTransaction: (CordaPersistence) -> T): T {
        val props = configuration.dataSourceProperties
        if (props.isNotEmpty()) {
            val database = configureDatabase(props, configuration.database, identityService, schemaService)
            // Now log the vendor string as this will also cause a connection to be tested eagerly.
            database.transaction {
                log.info("Connected to ${database.dataSource.connection.metaData.databaseProductName} database.")
            }
            runOnStop += database::close
            return database.transaction {
                insideTransaction(database)
            }
        } else {
            throw DatabaseConfigurationException("There must be a database configured.")
        }
    }

    private fun makeNotaryService(tokenizableServices: MutableList<Any>, database: CordaPersistence): NotaryService? {
        return configuration.notary?.let {
            makeCoreNotaryService(it, database).also {
                tokenizableServices.add(it)
                runOnStop += it::stop
                installCoreFlow(NotaryFlow.Client::class, it::createServiceFlow)
                log.info("Running core notary: ${it.javaClass.name}")
                it.start()
            }
        }
    }

    open protected fun checkNetworkMapIsInitialized() {
        if (!services.networkMapCache.loadDBSuccess) {
            // TODO: There should be a consistent approach to configuration error exceptions.
            throw NetworkMapCacheEmptyException()
        }
    }

    protected open fun makeKeyManagementService(identityService: IdentityServiceInternal, keyPairs: Set<KeyPair>): KeyManagementService {
        return PersistentKeyManagementService(identityService, keyPairs)
    }

    private fun retrieveNetworkParameters() {
        val networkParamsFile = configuration.baseDirectory.list { paths ->
            paths.filter { it.fileName.toString() == NETWORK_PARAMS_FILE_NAME }.findFirst().orElse(null)
        }

        networkParameters = if (networkParamsFile != null) {
            networkParamsFile.readAll().deserialize<SignedData<NetworkParameters>>().verified()
        } else {
            log.info("No network-parameters file found. Expecting network parameters to be available from the network map.")
            val networkMapClient = checkNotNull(networkMapClient) {
                "Node hasn't been configured to connect to a network map from which to get the network parameters"
            }
            val (networkMap, _) = networkMapClient.getNetworkMap()
            val signedParams = checkNotNull(networkMapClient.getNetworkParameter(networkMap.networkParameterHash)) {
                "Failed loading network parameters from network map server"
            }
            val verifiedParams = signedParams.verified()
            signedParams.serialize().open().copyTo(configuration.baseDirectory / NETWORK_PARAMS_FILE_NAME)
            verifiedParams
        }

        log.info("Loaded network parameters: $networkParameters")
        check(networkParameters.minimumPlatformVersion <= versionInfo.platformVersion) {
            "Node's platform version is lower than network's required minimumPlatformVersion"
        }
    }

    private fun makeCoreNotaryService(notaryConfig: NotaryConfig, database: CordaPersistence): NotaryService {
        val notaryKey = myNotaryIdentity?.owningKey ?: throw IllegalArgumentException("No notary identity initialized when creating a notary service")
        return notaryConfig.run {
            if (raft != null) {
                val uniquenessProvider = RaftUniquenessProvider(configuration, database, services.monitoringService.metrics, raft)
                (if (validating) ::RaftValidatingNotaryService else ::RaftNonValidatingNotaryService)(services, notaryKey, uniquenessProvider)
            } else if (bftSMaRt != null) {
                if (validating) throw IllegalArgumentException("Validating BFTSMaRt notary not supported")
                BFTNonValidatingNotaryService(services, notaryKey, bftSMaRt, makeBFTCluster(notaryKey, bftSMaRt))
            } else {
                (if (validating) ::ValidatingNotaryService else ::SimpleNotaryService)(services, notaryKey)
            }
        }
    }

    protected open fun makeBFTCluster(notaryKey: PublicKey, bftSMaRtConfig: BFTSMaRtConfiguration): BFTSMaRt.Cluster {
        return object : BFTSMaRt.Cluster {
            override fun waitUntilAllReplicasHaveInitialized() {
                log.warn("A BFT replica may still be initializing, in which case the upcoming consensus change may cause it to spin.")
            }
        }
    }

    private fun makeIdentityService(identityCert: X509Certificate): PersistentIdentityService {
        val trustStore = KeyStoreWrapper(configuration.trustStoreFile, configuration.trustStorePassword)
        val caKeyStore = KeyStoreWrapper(configuration.nodeKeystore, configuration.keyStorePassword)
        val trustRoot = trustStore.getX509Certificate(X509Utilities.CORDA_ROOT_CA)
        val clientCa = caKeyStore.getCertificateAndKeyPair(X509Utilities.CORDA_CLIENT_CA)
        val caCertificates = arrayOf(identityCert, clientCa.certificate.cert)
        return PersistentIdentityService(trustRoot, *caCertificates)
    }

    protected abstract fun makeTransactionVerifierService(): TransactionVerifierService

    open fun stop() {
        // TODO: We need a good way of handling "nice to have" shutdown events, especially those that deal with the
        // network, including unsubscribing from updates from remote services. Possibly some sort of parameter to stop()
        // to indicate "Please shut down gracefully" vs "Shut down now".
        // Meanwhile, we let the remote service send us updates until the acknowledgment buffer overflows and it
        // unsubscribes us forcibly, rather than blocking the shutdown process.

        // Run shutdown hooks in opposite order to starting
        for (toRun in runOnStop.reversed()) {
            toRun()
        }
        runOnStop.clear()
        _started = null
    }

    protected abstract fun makeMessagingService(database: CordaPersistence, info: NodeInfo): MessagingService
    protected abstract fun startMessagingService(rpcOps: RPCOps)

    private fun obtainIdentity(notaryConfig: NotaryConfig?): Pair<PartyAndCertificate, KeyPair> {
        val keyStore = KeyStoreWrapper(configuration.nodeKeystore, configuration.keyStorePassword)

        val (id, singleName) = if (notaryConfig == null || !notaryConfig.isClusterConfig) {
            // Node's main identity or if it's a single node notary
            Pair(DevIdentityGenerator.NODE_IDENTITY_ALIAS_PREFIX, configuration.myLegalName)
        } else {
            // The node is part of a distributed notary whose identity must already be generated beforehand.
            Pair(DevIdentityGenerator.DISTRIBUTED_NOTARY_ALIAS_PREFIX, null)
        }
        // TODO: Integrate with Key management service?
        val privateKeyAlias = "$id-private-key"

        if (!keyStore.containsAlias(privateKeyAlias)) {
            singleName ?: throw IllegalArgumentException(
                    "Unable to find in the key store the identity of the distributed notary ($id) the node is part of")
            // TODO: Remove use of [IdentityGenerator.generateToDisk].
            log.info("$privateKeyAlias not found in key store ${configuration.nodeKeystore}, generating fresh key!")
            keyStore.storeLegalIdentity(singleName, privateKeyAlias, generateKeyPair())
        }

        val (x509Cert, keyPair) = keyStore.getCertificateAndKeyPair(privateKeyAlias)

        // TODO: Use configuration to indicate composite key should be used instead of public key for the identity.
        val compositeKeyAlias = "$id-composite-key"
        val certificates = if (keyStore.containsAlias(compositeKeyAlias)) {
            // Use composite key instead if it exists
            val certificate = keyStore.getCertificate(compositeKeyAlias)
            // We have to create the certificate chain for the composite key manually, this is because we don't have a keystore
            // provider that understand compositeKey-privateKey combo. The cert chain is created using the composite key certificate +
            // the tail of the private key certificates, as they are both signed by the same certificate chain.
            listOf(certificate) + keyStore.getCertificateChain(privateKeyAlias).drop(1)
        } else {
            keyStore.getCertificateChain(privateKeyAlias).let {
                check(it[0].toX509CertHolder() == x509Cert) { "Certificates from key store do not line up!" }
                it.asList()
            }
        }

        val nodeCert = certificates[0] as? X509Certificate ?: throw ConfigurationException("Node certificate must be an X.509 certificate")
        val subject = CordaX500Name.build(nodeCert.subjectX500Principal)
        // TODO Include the name of the distributed notary, which the node is part of, in the notary config so that we
        // can cross-check the identity we get from the key store
        if (singleName != null && subject != singleName) {
            throw ConfigurationException("The name '$singleName' for $id doesn't match what's in the key store: $subject")
        }

        val certPath = X509CertificateFactory().generateCertPath(certificates)
        return Pair(PartyAndCertificate(certPath), keyPair)
    }

    protected open fun generateKeyPair() = cryptoGenerateKeyPair()
    protected open fun makeVaultService(keyManagementService: KeyManagementService, stateLoader: StateLoader, hibernateConfig: HibernateConfiguration): VaultServiceInternal {
        return NodeVaultService(platformClock, keyManagementService, stateLoader, hibernateConfig)
    }

    private inner class ServiceHubInternalImpl(
            override val identityService: IdentityService,
            // Place the long term identity key in the KMS. Eventually, this is likely going to be separated again because
            // the KMS is meant for derived temporary keys used in transactions, and we're not supposed to sign things with
            // the identity key. But the infrastructure to make that easy isn't here yet.
            override val keyManagementService: KeyManagementService,
            override val schemaService: SchemaService,
            override val validatedTransactions: WritableTransactionStorage,
            override val monitoringService: MonitoringService,
            override val cordappProvider: CordappProviderInternal,
            override val database: CordaPersistence,
            override val myInfo: NodeInfo,
            override val networkMapCache: NetworkMapCacheInternal
    ) : SingletonSerializeAsToken(), ServiceHubInternal, StateLoader by validatedTransactions {
        override val rpcFlows = ArrayList<Class<out FlowLogic<*>>>()
        override val stateMachineRecordedTransactionMapping = DBTransactionMappingStorage()
        override val auditService = DummyAuditService()
        override val transactionVerifierService by lazy { makeTransactionVerifierService() }
        override val vaultService by lazy { makeVaultService(keyManagementService, validatedTransactions, database.hibernateConfig) }
        override val contractUpgradeService by lazy { ContractUpgradeServiceImpl() }
        override val attachments: AttachmentStorage get() = this@AbstractNode.attachments
        override val networkService: MessagingService get() = network
        override val clock: Clock get() = platformClock
        override val configuration: NodeConfiguration get() = this@AbstractNode.configuration
        override fun <T : SerializeAsToken> cordaService(type: Class<T>): T {
            require(type.isAnnotationPresent(CordaService::class.java)) { "${type.name} is not a Corda service" }
            return cordappServices.getInstance(type) ?: throw IllegalArgumentException("Corda service ${type.name} does not exist")
        }

        override fun getFlowFactory(initiatingFlowClass: Class<out FlowLogic<*>>): InitiatedFlowFactory<*>? {
            return flowFactories[initiatingFlowClass]
        }

        override fun recordTransactions(statesToRecord: StatesToRecord, txs: Iterable<SignedTransaction>) {
            database.transaction {
                super.recordTransactions(statesToRecord, txs)
            }
        }

        override fun jdbcSession(): Connection = database.createSession()
    }
}

internal class FlowStarterImpl(private val serverThread: AffinityExecutor, private val smm: StateMachineManager) : FlowStarter {
    override fun <T> startFlow(logic: FlowLogic<T>, context: InvocationContext): CordaFuture<FlowStateMachine<T>> {
        return serverThread.fetchFrom { smm.startFlow(logic, context) }
    }
}

class ConfigurationException(message: String) : CordaException(message)

/**
 * Thrown when a node is about to start and its network map cache doesn't contain any node.
 */
internal class NetworkMapCacheEmptyException : Exception()

fun configureDatabase(dataSourceProperties: Properties,
                      databaseConfig: DatabaseConfig,
                      identityService: IdentityService,
                      schemaService: SchemaService = NodeSchemaService()): CordaPersistence {
    // Register the AbstractPartyDescriptor so Hibernate doesn't warn when encountering AbstractParty. Unfortunately
    // Hibernate warns about not being able to find a descriptor if we don't provide one, but won't use it by default
    // so we end up providing both descriptor and converter. We should re-examine this in later versions to see if
    // either Hibernate can be convinced to stop warning, use the descriptor by default, or something else.
    JavaTypeDescriptorRegistry.INSTANCE.addDescriptor(AbstractPartyDescriptor(identityService))
    val config = HikariConfig(dataSourceProperties)
    val dataSource = HikariDataSource(config)
    val attributeConverters = listOf(AbstractPartyToX500NameAsStringConverter(identityService))
    return CordaPersistence(dataSource, databaseConfig, schemaService.schemaOptions.keys, attributeConverters)
}

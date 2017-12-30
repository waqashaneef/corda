package net.corda.node.utilities.registration

import net.corda.core.crypto.Crypto
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.cert
import net.corda.core.internal.concurrent.transpose
import net.corda.core.internal.toX509CertHolder
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.minutes
import net.corda.finance.DOLLARS
import net.corda.finance.flows.CashIssueAndPaymentFlow
import net.corda.nodeapi.internal.crypto.CertificateAndKeyPair
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509CertificateFactory
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.crypto.X509Utilities.CORDA_CLIENT_CA
import net.corda.nodeapi.internal.crypto.X509Utilities.CORDA_INTERMEDIATE_CA
import net.corda.nodeapi.internal.crypto.X509Utilities.CORDA_ROOT_CA
import net.corda.testing.ROOT_CA
import net.corda.testing.SerializationEnvironmentRule
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.driver.PortAllocation
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.internal.CompatibilityZoneParams
import net.corda.testing.node.internal.internalDriver
import net.corda.testing.node.internal.network.NetworkMapServer
import net.corda.testing.singleIdentity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.junit.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.security.KeyPair
import java.security.cert.CertPath
import java.security.cert.CertPathValidatorException
import java.security.cert.Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.ws.rs.*
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

class NodeRegistrationTest {
    companion object {
        private val notaryName = CordaX500Name("NotaryService", "Zurich", "CH")
    }

    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule(true)

    private val portAllocation = PortAllocation.Incremental(13000)
    private val registrationHandler = RegistrationHandler(ROOT_CA)

    private lateinit var server: NetworkMapServer
    private lateinit var serverHostAndPort: NetworkHostAndPort

    @Before
    fun startServer() {
        server = NetworkMapServer(1.minutes, portAllocation.nextHostAndPort(), ROOT_CA, "localhost", registrationHandler)
        serverHostAndPort = server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun `node registration correct root cert`() {
        val compatibilityZone = CompatibilityZoneParams(
                URL("http://$serverHostAndPort"),
                publishNotaries = { server.networkParameters = testNetworkParameters(it) },
                rootCert = ROOT_CA.certificate.cert)
        internalDriver(
                portAllocation = portAllocation,
                compatibilityZone = compatibilityZone,
                initialiseSerialization = false,
                notarySpecs = listOf(NotarySpec(notaryName)),
                extraCordappPackagesToScan = listOf("net.corda.finance")
        ) {
            val aliceName = "Alice"
            val genevieveName = "Genevieve"

            val nodes = listOf(
                    startNode(providedName = CordaX500Name(aliceName, "London", "GB")),
                    startNode(providedName = CordaX500Name(genevieveName, "London", "GB")),
                    defaultNotaryNode
            ).transpose().getOrThrow()
            val (alice, genevieve) = nodes

            assertThat(registrationHandler.idsPolled).containsOnly(aliceName, genevieveName, notaryName.organisation)

            // Check the nodes can communicate among themselves (and the notary).
            val anonymous = false
            genevieve.rpc.startFlow(
                    ::CashIssueAndPaymentFlow,
                    1000.DOLLARS,
                    OpaqueBytes.of(12),
                    alice.nodeInfo.singleIdentity(),
                    anonymous,
                    defaultNotaryIdentity
            ).returnValue.getOrThrow()
        }
    }

    @Test
    fun `node registration wrong root cert`() {
        val someRootCert = X509Utilities.createSelfSignedCACertificate(
                CordaX500Name("Integration Test Corda Node Root CA", "R3 Ltd", "London", "GB"),
                Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME))
        val compatibilityZone = CompatibilityZoneParams(
                URL("http://$serverHostAndPort"),
                publishNotaries = { server.networkParameters = testNetworkParameters(it) },
                rootCert = someRootCert.cert)
        internalDriver(
                portAllocation = portAllocation,
                compatibilityZone = compatibilityZone,
                initialiseSerialization = false,
                notarySpecs = listOf(NotarySpec(notaryName)),
                startNodesInProcess = true  // We need to run the nodes in the same process so that we can capture the correct exception
        ) {
            assertThatThrownBy {
                defaultNotaryNode.getOrThrow()
            }.isInstanceOf(CertPathValidatorException::class.java)
        }
    }
}

@Path("certificate")
class RegistrationHandler(private val rootCertAndKeyPair: CertificateAndKeyPair) {
    private val certPaths = HashMap<String, CertPath>()
    val idsPolled = HashSet<String>()

    @POST
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.TEXT_PLAIN)
    fun registration(input: InputStream): Response {
        val certificationRequest = input.use { JcaPKCS10CertificationRequest(it.readBytes()) }
        val (certPath, name) = createSignedClientCertificate(
                certificationRequest,
                rootCertAndKeyPair.keyPair,
                arrayOf(rootCertAndKeyPair.certificate.cert))
        require(!name.organisation.contains("\\s".toRegex())) { "Whitespace in the organisation name not supported" }
        certPaths[name.organisation] = certPath
        return Response.ok(name.organisation).build()
    }

    @GET
    @Path("{id}")
    fun reply(@PathParam("id") id: String): Response {
        idsPolled += id
        return buildResponse(certPaths[id]!!.certificates)
    }

    private fun buildResponse(certificates: List<Certificate>): Response {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            listOf(CORDA_CLIENT_CA, CORDA_INTERMEDIATE_CA, CORDA_ROOT_CA).zip(certificates).forEach {
                zip.putNextEntry(ZipEntry("${it.first}.cer"))
                zip.write(it.second.encoded)
                zip.closeEntry()
            }
        }
        return Response.ok(baos.toByteArray())
                .type("application/zip")
                .header("Content-Disposition", "attachment; filename=\"certificates.zip\"").build()
    }

    private fun createSignedClientCertificate(certificationRequest: PKCS10CertificationRequest,
                                              caKeyPair: KeyPair,
                                              caCertPath: Array<Certificate>): Pair<CertPath, CordaX500Name> {
        val request = JcaPKCS10CertificationRequest(certificationRequest)
        val name = CordaX500Name.parse(request.subject.toString())
        val nodeCaCert = X509Utilities.createCertificate(
                CertificateType.NODE_CA,
                caCertPath.first().toX509CertHolder(),
                caKeyPair,
                name,
                request.publicKey,
                nameConstraints = null)
        val certPath = X509CertificateFactory().generateCertPath(nodeCaCert.cert, *caCertPath)
        return Pair(certPath, name)
    }
}

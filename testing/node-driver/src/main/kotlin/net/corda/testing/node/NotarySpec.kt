package net.corda.testing.node

import net.corda.core.identity.CordaX500Name
import net.corda.node.services.config.VerifierType
import net.corda.nodeapi.internal.config.User

data class NotarySpec(
        val name: CordaX500Name,
        val validating: Boolean = true,
        val rpcUsers: List<User> = emptyList(),
        val verifierType: VerifierType = VerifierType.InMemory,
        val cluster: ClusterSpec? = null
)

sealed class ClusterSpec {
    abstract val clusterSize: Int

    data class Raft(
            override val clusterSize: Int,
            /**
             * If *true*, the cluster will use the same shared "singular" key pair for the service identity,
             * if *false* – a shared composite public key with individual private keys.
             */
            val singularServiceIdentity: Boolean = false
    ) : ClusterSpec() {
        init {
            require(clusterSize > 0)
        }
    }
}
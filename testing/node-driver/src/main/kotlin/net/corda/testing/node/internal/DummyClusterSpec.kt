package net.corda.testing.node.internal

import net.corda.testing.node.ClusterSpec

/**
 * Only used for testing the notary communication path. Can be configured to act as a Raft (singular identity),
 * or a BFT (composite key identity) notary service.
 */
data class DummyClusterSpec(
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
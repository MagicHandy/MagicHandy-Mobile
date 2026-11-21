package com.magichandy.mobile.core.api

/**
 * Result of an explicit read-only Intiface lookup. An empty successful list is a reachable bridge.
 */
public sealed interface BridgeDiscovery<out T> {
    public val choices: List<T>

    public data class Found<T>(override val choices: List<T>) : BridgeDiscovery<T>

    public data class Failed(public val reason: FormReason) : BridgeDiscovery<Nothing> {
        override val choices: List<Nothing>
            get() = emptyList()
    }
}

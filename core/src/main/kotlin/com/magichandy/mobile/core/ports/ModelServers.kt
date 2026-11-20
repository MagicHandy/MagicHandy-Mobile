package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.*

/**
 * Probes only a user-entered address. Detection and a bounded, inert readiness request use the same
 * peer.
 */
public fun interface ModelServers {
    public suspend fun check(address: String, model: String?, level: CapabilityLevel?): ServerCheck
}

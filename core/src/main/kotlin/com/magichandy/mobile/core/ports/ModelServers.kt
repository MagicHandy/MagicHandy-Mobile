package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ServerCheck

/**
 * Probes only a user-entered address. Detection and a bounded, inert readiness request use the same
 * peer.
 */
public data class ServerAssessment(
    public val check: ServerCheck,
    public val refused: Boolean = false,
)

/** Discover and verify a named model connection. */
public fun interface ModelServers {
    public suspend fun verify(
        address: String,
        model: String,
        level: CapabilityLevel,
    ): ServerAssessment = ServerAssessment(check(address, model, level))

    public suspend fun check(address: String, model: String?, level: CapabilityLevel?): ServerCheck
}

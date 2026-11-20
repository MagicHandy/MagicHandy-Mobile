package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.*
import kotlinx.coroutines.flow.StateFlow

/** A user-owned channel strength handshake. A planner cannot reach this port. */
public interface CoyoteControls {
    /** Device-reported strengths. Null until the first valid B1. */
    public val reported: StateFlow<DeviceOutput.Channels?>

    /** Queue one explicit manual step; transmission requires a current engine output lease. */
    public fun step(
        channel: CoyoteChannel,
        increase: Boolean,
        epoch: Epoch,
        generation: Long,
    ): Boolean
}

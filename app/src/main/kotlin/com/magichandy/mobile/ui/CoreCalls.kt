package com.magichandy.mobile.ui

import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.CoyoteChannel
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.IntifaceChoice
import com.magichandy.mobile.core.api.MagicHandyCore

/**
 * The core's direct calls that aren't intents (`docs/backend/contract-changes.md`). The root takes
 * them from [MagicHandyCore]; previews and sample states use [None].
 *
 * @property findVibrators lists the vibrators an Intiface bridge reports at an address. It returns
 *   an empty list when the bridge can't be reached, and never moves anything.
 * @property setRefusalFallback sets the local connection that retries a refused message once, or
 *   null for Off.
 * @property setCoyoteLimits sets a disconnected Coyote's channel limits, from 0 to 200.
 * @property stepCoyote moves one channel of a playing Coyote by one unit, within its limit.
 */
class CoreCalls(
    val findVibrators: suspend (address: String) -> List<IntifaceChoice>,
    val setRefusalFallback: (connection: ConnectionId?) -> Unit,
    val setCoyoteLimits: (device: DeviceId, maximumA: Int, maximumB: Int) -> Unit,
    val stepCoyote: (device: DeviceId, channel: CoyoteChannel, increase: Boolean) -> Unit,
) {
    companion object {
        /** Calls that do nothing, for previews and sample states. */
        val None =
            CoreCalls(
                findVibrators = { emptyList() },
                setRefusalFallback = {},
                setCoyoteLimits = { _, _, _ -> },
                stepCoyote = { _, _, _ -> },
            )

        /** The calls of [core]. */
        fun of(core: MagicHandyCore): CoreCalls =
            CoreCalls(
                findVibrators = { core.intifaceVibrators(it) },
                setRefusalFallback = core::setRefusalFallback,
                setCoyoteLimits = core::setCoyoteLimits,
                stepCoyote = core::stepCoyote,
            )
    }
}

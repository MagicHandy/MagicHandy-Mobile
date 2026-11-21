package com.magichandy.mobile.ui

import com.magichandy.mobile.core.api.BridgeDiscovery
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.CoyoteChannel
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DiagnosticsReport
import com.magichandy.mobile.core.api.FormIntent
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.IntifaceChoice
import com.magichandy.mobile.core.api.IntifaceLinearChoice
import com.magichandy.mobile.core.api.MagicHandyCore

/**
 * The core's direct calls that aren't intents (`docs/backend/contract-changes.md`). The root takes
 * them from [MagicHandyCore]; previews and sample states use [None].
 *
 * @property findVibrators reports devices or a specific failure from an explicitly named bridge.
 * @property findLinearActuators performs the same read-only lookup for stroker features.
 * @property submit sends a form and waits for the core to save it or say why it can't.
 * @property diagnosticsReport builds the redacted report that App settings shares.
 * @property setRefusalFallback sets the local connection that retries a refused message once, or
 *   null for Off.
 * @property setCoyoteLimits sets a disconnected Coyote's channel limits, from 0 to 200.
 * @property stepCoyote moves one channel of a playing Coyote by one unit, within its limit.
 */
class CoreCalls(
    val findVibrators: suspend (address: String) -> BridgeDiscovery<IntifaceChoice>,
    val findLinearActuators: suspend (address: String) -> BridgeDiscovery<IntifaceLinearChoice>,
    val submit: suspend (form: FormIntent) -> FormResult,
    val diagnosticsReport: suspend () -> DiagnosticsReport,
    val setRefusalFallback: (connection: ConnectionId?) -> Unit,
    val setCoyoteLimits: (device: DeviceId, maximumA: Int, maximumB: Int) -> Unit,
    val stepCoyote: (device: DeviceId, channel: CoyoteChannel, increase: Boolean) -> Unit,
    val bridgeAddressForSetup: (DeviceId) -> String? = { null },
) {
    companion object {
        /** Calls that do nothing, for previews and sample states. Forms are refused. */
        val None =
            CoreCalls(
                findVibrators = { BridgeDiscovery.Found(emptyList()) },
                findLinearActuators = { BridgeDiscovery.Found(emptyList()) },
                submit = { FormResult.Rejected(FormReason.Unsupported) },
                diagnosticsReport = { DiagnosticsReport("MagicHandy-diagnostics.txt", "") },
                setRefusalFallback = {},
                setCoyoteLimits = { _, _, _ -> },
                stepCoyote = { _, _, _ -> },
            )

        /** The calls of [core]. */
        fun of(core: MagicHandyCore): CoreCalls =
            CoreCalls(
                findVibrators = { core.discoverIntifaceVibrators(it) },
                findLinearActuators = { core.discoverIntifaceLinears(it) },
                submit = { core.submit(it) },
                diagnosticsReport = { core.diagnosticsReport() },
                setRefusalFallback = core::setRefusalFallback,
                setCoyoteLimits = core::setCoyoteLimits,
                stepCoyote = core::stepCoyote,
                bridgeAddressForSetup = core::bridgeAddressForSetup,
            )
    }
}

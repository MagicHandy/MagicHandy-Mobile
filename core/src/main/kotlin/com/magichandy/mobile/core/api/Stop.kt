package com.magichandy.mobile.core.api

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The outcome of the latest Stop, per device (system design §2).
 *
 * @property pressCount explicit Stop presses since the receipt appeared. A routine stop that needs
 *   a local check can open a receipt with zero presses. Uncertainty survives repeated presses.
 * @property outcomes one per targeted device still shown, ordered unconfirmed, pending and
 *   confirmed. A local check removes its row. Clearing the final row dismisses the receipt.
 */
public data class StopReceipt(
    public val pressCount: Int,
    public val at: Instant,
    public val outcomes: List<DeviceStopOutcome>,
    public val didTurnOffAutopilot: Boolean,
    public val didCancelReply: Boolean,
) {
    /** True when there was nothing to stop: "No connected device to stop." */
    public val isNoDevice: Boolean
        get() = outcomes.isEmpty()

    /** True only when every targeted device confirmed: "Stopped." */
    public val isAllConfirmed: Boolean
        get() = outcomes.isNotEmpty() && outcomes.all { it.outcome == StopOutcome.Confirmed }
}

/**
 * One device's Stop outcome.
 *
 * @property confirmedIn the time from the press to the acknowledgement, when confirmed.
 * @property cause why it is unconfirmed; null otherwise.
 */
public data class DeviceStopOutcome(
    public val device: DeviceId,
    public val name: String,
    public val family: DeviceFamily,
    public val connection: ConnectionKind,
    public val outcome: StopOutcome,
    public val confirmedIn: Duration?,
    public val cause: UnconfirmedCause?,
    public val afterLoss: AfterLossBehaviour,
)

/** A Stop outcome. Declared in display order: uncertain first. */
public enum class StopOutcome {
    /** No acknowledgement, or an acknowledgement that can't prove the device stopped. */
    Unconfirmed,

    /** A reply can still arrive before its deadline. */
    Pending,

    /** The device acknowledged Stop. */
    Confirmed,
}

/** Why a Stop is unconfirmed. */
public enum class UnconfirmedCause {
    NoReply,
    WriteFailed,
    /** Only a bridge, such as Intiface Central, replied. */
    BridgeOnly,
    LinkLost,
    Offline,
    /** The app stopped before an acknowledgement could arrive. */
    AppStopped,
}

/**
 * A run that a cold start found unresolved in the journal. Motion and Autopilot stay off.
 *
 * @property stoppedAt the last time the journal recorded the run as live.
 * @property outcomes the recovery Stop's outcome per device, uncertain first.
 */
public data class InterruptedRun(
    public val stoppedAt: Instant,
    public val outcomes: List<DeviceStopOutcome>,
    public val wasAutopilotOn: Boolean,
)

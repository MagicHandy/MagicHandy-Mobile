package com.magichandy.mobile.core.api

import kotlinx.coroutines.flow.StateFlow

/**
 * The one object the Android app talks to.
 *
 * The UI observes [state] and sends [UserIntent]s through [dispatch]. Stop never goes through
 * [dispatch]: every Stop surface calls [emergencyStop] directly, so a busy intent queue, a slow
 * model reply or stalled storage can never delay it (plan §5.2, system design §2).
 */
public interface MagicHandyCore {
    /** The latest immutable snapshot of everything the UI shows. */
    public val state: StateFlow<AppState>

    /** Read a redacted report of at most 256 KiB. Sharing is an explicit UI action. */
    public suspend fun diagnosticsReport(): DiagnosticsReport =
        DiagnosticsReport("MagicHandy-diagnostics.txt", "Diagnostics unavailable\n")

    /** Wait for the result of this exact form. Existing implementations can report Unsupported. */
    public suspend fun submit(intent: FormIntent): FormResult =
        FormResult.Rejected(FormReason.Unsupported)

    /** Queues [intent] and returns at once. Its effect appears in [state]. */
    public fun dispatch(intent: UserIntent)

    /**
     * Stops every device now. Safe to call from any thread, any number of times.
     *
     * The call advances the Stop epoch, closes admission, cancels the chat turn, Autopilot and
     * retargets, and sends Stop to every owner in parallel. The outcome arrives in
     * [AppState.stopReceipt], and is never claimed before a device acknowledges it.
     */
    public fun emergencyStop(source: StopSource)

    /** Lets Android retain a Stop delivery window after calling [emergencyStop] directly. */
    public suspend fun awaitEmergencyStop() {}

    /** Opt in to one refusal retry using a specific local server, or disable it with null. */
    public fun setRefusalFallback(connection: ConnectionId?) {}

    /** Refresh the OS access shown on Devices and App settings. */
    public fun updateSystemAccess(notificationsAllowed: Boolean, batteryUnrestricted: Boolean) {}

    /** Publish the observed tile lifecycle state from System UI. */
    public fun updateStopTileAdded(added: Boolean) {}

    /** Persist the first notification prompt across activity and process recreation. */
    public fun notificationPermissionRequested() {}

    /** Activity visibility and memory callbacks never change the epoch of a service-owned run. */
    public fun appBackgrounded() {}

    /**
     * Voice input started ([isActive] true) or stopped listening. While it listens, the person is
     * still talking to the app with the screen off, so [appBackgrounded] leaves a streaming reply
     * running instead of interrupting it.
     */
    public fun setVoiceActive(isActive: Boolean) {}

    public fun trimMemory() {}

    /**
     * List reachable bridge devices or explain why the explicitly named host could not be checked.
     */
    public suspend fun discoverIntifaceVibrators(address: String): BridgeDiscovery<IntifaceChoice> =
        BridgeDiscovery.Found(intifaceVibrators(address))

    /** List reachable linear features or explain the lookup failure. */
    public suspend fun discoverIntifaceLinears(
        address: String
    ): BridgeDiscovery<IntifaceLinearChoice> =
        BridgeDiscovery.Found(intifaceLinearActuators(address))

    /**
     * List eligible vibrators at a user-supplied bridge address. An unavailable bridge yields an
     * empty list.
     */
    public suspend fun intifaceVibrators(address: String): List<IntifaceChoice> = emptyList()

    /** Saved bridge endpoint for reselecting an explicitly opened Intiface device. */
    public fun bridgeAddressForSetup(device: DeviceId): String? = null

    /** Read the linear features of an explicitly named bridge, without selecting or moving one. */
    public suspend fun intifaceLinearActuators(address: String): List<IntifaceLinearChoice> =
        emptyList()

    /** The VacuGlide's physical speed buttons adjust only the top of its own motor range. */
    public fun stepTempoRange(device: DeviceId, direction: StepDirection) {}

    /**
     * Set the personal ceilings while disconnected. They are written on the next verified
     * connection.
     */
    public fun setCoyoteLimits(device: DeviceId, maximumA: Int, maximumB: Int) {}

    /** One explicit manual strength step while the selected Coyote has a reported baseline. */
    public fun stepCoyote(device: DeviceId, channel: CoyoteChannel, increase: Boolean) {}
}

/** Where a Stop came from, for the receipt and diagnostics. */
public enum class StopSource {
    /** The red row at the bottom of every screen. */
    AppFooter,

    /** The ongoing notification's action, including on the lock screen. */
    Notification,

    /** The "Stop motion" Quick Settings tile. */
    QuickSettingsTile,

    /** The "Stop all motion" TalkBack action. */
    TalkBackAction,

    /** A typed "stop", recognized before any inference. */
    TypedStop,

    /** A device button that the desktop maps to Emergency Stop, such as Autoblow's. */
    DeviceButton,

    /** The task was swiped away. */
    TaskRemoved,

    /** A cold start found an unresolved run journal. */
    Recovery,

    /** An authorized, validated Advanced chat decision to stop playback. */
    ModelReply,
    /** A playing VacuGlide's one-minute check-in expired without an answer. */
    CheckInTimeout,

    /** The foreground service was destroyed while it still owned device work. */
    ServiceLost,

    /** A spoken "stop", recognized on the phone by voice input before any inference. */
    SpokenStop,
}

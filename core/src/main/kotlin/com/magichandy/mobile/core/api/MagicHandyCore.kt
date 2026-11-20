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
}

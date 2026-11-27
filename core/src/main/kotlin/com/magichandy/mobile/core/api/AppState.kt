package com.magichandy.mobile.core.api

import kotlin.time.Duration

/**
 * Everything the UI shows, as one immutable snapshot.
 *
 * @property stopReceipt the outcome of the latest Stop, shown above the footer until dismissed.
 * @property interruptedRun set when a cold start found an unresolved run journal. The app then
 *   opens Run interrupted before anything else.
 * @property banners persistent notices for the current screen, most urgent first.
 */
public data class AppState(
    public val firstRun: FirstRunState,
    public val chat: ChatState,
    public val run: RunState,
    public val autopilot: AutopilotState,
    public val devices: DevicesState,
    public val settings: SessionSettings,
    public val models: ModelsState,
    public val app: AppSettingsState,
    public val stopReceipt: StopReceipt?,
    public val interruptedRun: InterruptedRun?,
    public val banners: List<Banner>,
    /** Last non-Stop action that needs attention. Cleared by the next user action. */
    public val backendNotice: BackendNotice? = null,
    /** Whether durable initial settings and models are available for UI routing. */
    public val startup: StartupState = StartupState.Ready,
    /**
     * Advances with every Stop from any surface, including Stops that leave no receipt, such as
     * switching motion off or a device being lost. Voice input ends listening when it changes, and
     * drops anything heard before it.
     */
    public val stopEpoch: Long = 0,
)

/** Storage loading does not delay direct Stop or its recovery attempts. */
public enum class StartupState {
    Loading,
    Ready,
    StorageUnavailable,
}

/** A recoverable action outcome for setup and controls outside the first verified backend path. */
public enum class BackendNotice {
    /** The requested capability is unavailable in this backend build. */
    FeatureUnavailable,
    /** A storage operation failed. Retry the requested action. */
    StorageUnavailable,
    /** A settings edit conflicted with a newer edit to the same field. Reopen the control. */
    SettingsConflict,
    /** Resolve the outstanding Stop on Devices before starting another run. */
    ReconcileDevices,
    /** Stop the active run before starting a separate run or motor test. */
    RunAlreadyActive,
}

/**
 * First-run state. Chat stays closed until the adults-only acknowledgement is given.
 *
 * @property isSetupDone false on a fresh install until the person finishes setup's last step
 *   ([UserIntent.FinishSetup]). An install that acknowledged adults only before setup existed
 *   counts as done.
 */
public data class FirstRunState(
    public val hasAcknowledgedAdultsOnly: Boolean,
    public val isSetupDone: Boolean = true,
)

/** A persistent notice. Each names its cause; the UI gives it one next step. */
public sealed interface Banner {
    /** A device in the run lost its link, so the whole run stopped. */
    public data class DeviceLost(
        public val device: DeviceId,
        public val name: String,
        public val connection: ConnectionKind,
        public val afterLoss: AfterLossBehaviour,
    ) : Banner

    /** The model connection dropped during Autopilot. [RunState.phase] says what still plays. */
    public data object PlanningHeld : Banner

    /**
     * The planning model refused Autopilot's request for a reason that trying again won't fix, such
     * as an expired sign-in or a model the account can't use. Autopilot holds without retrying
     * until the model connection changes or the person tries again with
     * [UserIntent.ResumeAutopilot]. [RunState.phase] says what still plays.
     */
    public data class PlanningFailed(public val failure: ProviderReplyFailure) : Banner

    /** A VacuGlide check-in is due. No answer within [answerWithin] stops the run. */
    public data class CheckInDue(
        public val device: DeviceId,
        public val name: String,
        public val answerWithin: Duration,
    ) : Banner

    /** A resume lead-in failed to reach its target. The connected roster is being held paused. */
    public data object ResumePositionNotReached : Banner

    /** A start was refused before any motion. */
    public data class StartRefused(public val cause: StartRefusal) : Banner
}

/** Why a start was refused. */
public enum class StartRefusal {
    /** The run journal couldn't be armed: no journal, no start (plan §5.4). */
    JournalUnavailable,

    /** No connected device is in the next run. */
    NoDeviceInRun,

    /** The motion mode is Off. */
    MotionModeOff,

    /** The foreground service couldn't start, so a run couldn't survive the screen turning off. */
    ServiceUnavailable,
}

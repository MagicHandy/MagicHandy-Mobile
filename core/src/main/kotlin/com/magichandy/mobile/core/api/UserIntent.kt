package com.magichandy.mobile.core.api

import kotlin.time.Duration

/**
 * Everything the user can ask the core to do, except Stop, which has its own direct path
 * ([MagicHandyCore.emergencyStop]).
 *
 * Intents only ask. The core validates each against the current state and limits, and its effect,
 * or the reason it was refused, appears in [AppState].
 */
public sealed interface UserIntent {
    // First run

    /** Records the first-run adult acknowledgement required before chat or device use. */
    public data object AcknowledgeAdultsOnly : UserIntent

    // Chat

    /** Sends a message. A typed "stop" takes the Stop fast path before any inference. */
    public data class SendMessage(public val text: String) : UserIntent

    /** Resends the message whose reply was interrupted. */
    public data object SendAgain : UserIntent

    /** Opens an empty conversation with the selected persona. */
    public data object NewChat : UserIntent

    /** Opens the saved conversation identified by [chat]. */
    public data class OpenChat(public val chat: ChatId) : UserIntent

    // Motion

    /** Starts a run with the devices marked for it. Refusals arrive as [Banner.StartRefused]. */
    public data object StartMotion : UserIntent

    /**
     * Holds the current phase and roster. A device whose halt is unconfirmed requires
     * reconciliation.
     */
    public data object PauseMotion : UserIntent

    /** Continues a confirmed paused run on a fresh stream, applying the latest saved limits. */
    public data object ResumeMotion : UserIntent

    /** Turns the gentle hold on or off. */
    public data class SetEdge(public val isOn: Boolean) : UserIntent

    /** Adjusts [limit] one step in [direction], within the device's boundaries. */
    public data class StepLimit(public val limit: LimitKind, public val direction: StepDirection) :
        UserIntent

    // Autopilot

    /** Requests Autopilot planning for the selected devices. */
    public data object StartAutopilot : UserIntent

    /** Holds new plans. The current segment keeps playing. */
    public data object PauseAutopilot : UserIntent

    /** Resumes planning after an explicit pause. */
    public data object ResumeAutopilot : UserIntent

    /** Cancels future planning. Motion continues until you pause or stop it. */
    public data object TurnOffAutopilot : UserIntent

    /** Selects how frequently Autopilot may plan a change. */
    public data class SetChangeFrequency(public val frequency: ChangeFrequency) : UserIntent

    /** Selects the chat and adjustment behaviour for Autopilot check-ins. */
    public data class SetCheckIns(public val checkIns: CheckIns) : UserIntent

    /** Sets the finish target; null turns it off. */
    public data class SetFinishTarget(public val total: Duration?) : UserIntent

    /** Sets the planner's connection; null means "Same as chat". */
    public data class SetPlannerConnection(public val connection: ConnectionId?) : UserIntent

    // Session settings

    /** Choosing Off while the AI moves a device takes the Stop path first. */
    public data class SetMotionMode(public val mode: MotionMode) : UserIntent

    /** Chooses the motion style for subsequent plans. */
    public data class SetStyle(public val style: MotionStyle) : UserIntent

    /** Takes effect on the next turn. */
    public data class SetPersona(public val persona: PersonaId) : UserIntent

    /** Binds chat turns to the saved model [connection]. */
    public data class SetChatConnection(public val connection: ConnectionId) : UserIntent

    /** Enables or disables persona memory for future turns. */
    public data class SetMemory(public val isOn: Boolean) : UserIntent

    /** Clears stored memory for the current persona. */
    public data object ClearMemory : UserIntent

    // Devices

    /** Requests discovery for [family] after its setup requirements are met. */
    public data class StartScan(public val family: DeviceFamily) : UserIntent

    /** Ends the active discovery session. */
    public data object StopScan : UserIntent

    /** Connects to a scan result and verifies it before it can play. */
    public data class ConnectScanResult(public val result: ScanResultId, public val name: String) :
        UserIntent

    /** Saves a Handy Cloud connection using its secret connection key. */
    public data class AddCloudHandy(public val name: String, public val connectionKey: Secret) :
        FormIntent

    /** Adds an Autoblow Ultra or a VacuGlide by its device token. */
    public data class AddAutoblowDevice(
        public val family: DeviceFamily,
        public val name: String,
        public val deviceToken: Secret,
    ) : FormIntent

    /** Adds Intiface Central at [address], on this phone or on the local network. */
    public data class AddIntiface(
        public val address: String,
        public val deviceIndex: Int? = null,
        public val selection: String? = null,
    ) : FormIntent

    /** Save the linear feature chosen in the last lookup. Null selects the only offered feature. */
    public data class AddIntifaceLinear(
        public val address: String,
        public val deviceIndex: Int? = null,
        public val actuatorIndex: Int? = null,
        public val selection: String? = null,
    ) : FormIntent

    /** Connecting ends at Connected and idle. It never starts motion. */
    public data class Connect(public val device: DeviceId) : UserIntent

    /** Releases [device] after its Stop attempt, stopping the run if one is active. */
    public data class Disconnect(public val device: DeviceId) : UserIntent

    /** Marks a device for the next run. A running run's membership never changes. */
    public data class SetInNextRun(public val device: DeviceId, public val isIncluded: Boolean) :
        UserIntent

    /** Selects [device] as the lead for the next run. */
    public data class SetLead(public val device: DeviceId) : UserIntent

    /** Changes the display name of a saved device. */
    public data class Rename(public val device: DeviceId, public val name: String) : FormIntent

    /**
     * Handy HSP applies mounting at the next Start or Resume. Intiface linear endpoints capture
     * mounting when queued and use the current stroke window at wire submission.
     */
    public data class SetReversed(public val device: DeviceId, public val isReversed: Boolean) :
        UserIntent

    /** Checks the link without moving the device. */
    public data class CheckConnection(public val device: DeviceId) : UserIntent

    /** A short, slow test on this device only, while nothing else plays. */
    public data class TestMotion(public val device: DeviceId) : UserIntent

    /** Removes the saved device configuration. */
    public data class Remove(public val device: DeviceId) : FormIntent

    /** Records that the person has read the Coyote connection notes. */
    public data object AcknowledgeCoyoteNotes : UserIntent

    /** Sets the VacuGlide check-in interval; null turns check-ins off. */
    public data class SetCheckInInterval(public val device: DeviceId, public val every: Duration?) :
        UserIntent

    /** Allows or disables discrete valve steps on this VacuGlide. */
    public data class SetValveSteps(public val device: DeviceId, public val isAllowed: Boolean) :
        UserIntent

    /** Answers a due check-in: "I'm here". */
    public data class AnswerCheckIn(public val device: DeviceId) : UserIntent

    // Stop receipts and recovery

    /** The person checked [device] locally and it is stopped. */
    public data class ReconcileDevice(public val device: DeviceId) : UserIntent

    /** Dismisses a receipt once all its device outcomes are resolved. */
    public data object DismissStopReceipt : UserIntent

    /** Leaves Run interrupted. Unresolved devices stay listed on Devices. */
    public data object DismissInterruptedRun : UserIntent

    /** Reconnects the saved devices after Run interrupted. Never restarts motion. */
    public data object ReconnectDevices : UserIntent

    // Models

    /** Checks the supplied model server address. */
    public data class CheckServer(public val address: String) : UserIntent

    /** Selects a model from the checked server's available models. */
    public data class SelectServerModel(public val model: String) : UserIntent

    /** Saves a named server, selected model and supported contract level. */
    public data class SaveServer(
        public val name: String,
        public val address: String,
        public val model: String,
        public val capability: CapabilityLevel,
    ) : FormIntent

    /** Starts the browser sign-in flow for a ChatGPT connection. */
    public data object StartChatGptSignIn : UserIntent

    /** Cancels the current browser sign-in attempt. */
    public data object CancelSignIn : UserIntent

    /** Saves the OpenRouter credential, model and reasoning preference. */
    public data class SaveOpenRouter(
        public val apiKey: Secret,
        public val model: String,
        public val capability: CapabilityLevel,
        public val isReasoningOn: Boolean,
    ) : FormIntent

    /** Removes the model connection and its associated credential. */
    public data class RemoveConnection(public val connection: ConnectionId) : FormIntent

    // App

    /** Selects the app's colour palette. */
    public data class SetPalette(public val palette: Palette) : UserIntent

    /** Selects the app language or the system language preference. */
    public data class SetLanguage(public val language: AppLanguage) : UserIntent

    /** Requests an app version check. */
    public data object CheckForUpdate : UserIntent

    /** Deletes all conversations and persona memory together. */
    public data object DeleteAllChatsAndMemory : UserIntent
}

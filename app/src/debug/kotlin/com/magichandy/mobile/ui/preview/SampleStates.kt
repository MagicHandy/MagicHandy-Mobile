package com.magichandy.mobile.ui.preview

import com.magichandy.mobile.core.api.AppLanguage
import com.magichandy.mobile.core.api.AppSettingsState
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.AutopilotState
import com.magichandy.mobile.core.api.AutopilotStatus
import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ChangeFrequency
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.ChatState
import com.magichandy.mobile.core.api.ChatSummary
import com.magichandy.mobile.core.api.CheckIns
import com.magichandy.mobile.core.api.CommandReceipt
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.ControlKind
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.DeviceStopOutcome
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.core.api.DevicesState
import com.magichandy.mobile.core.api.EntryId
import com.magichandy.mobile.core.api.FinishTarget
import com.magichandy.mobile.core.api.FirstRunState
import com.magichandy.mobile.core.api.InterruptCause
import com.magichandy.mobile.core.api.InterruptedRun
import com.magichandy.mobile.core.api.LimitKind
import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.ModelsState
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.MotionSource
import com.magichandy.mobile.core.api.MotionStyle
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.RunEvent
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.RunState
import com.magichandy.mobile.core.api.ScanState
import com.magichandy.mobile.core.api.SendBlock
import com.magichandy.mobile.core.api.ServerCheck
import com.magichandy.mobile.core.api.SessionSettings
import com.magichandy.mobile.core.api.SignInState
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopReceipt
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.api.UpdateCheck
import com.magichandy.mobile.core.api.ValueChange
import com.magichandy.mobile.ui.preview.SampleDevices.coyote
import com.magichandy.mobile.ui.preview.SampleDevices.handy
import com.magichandy.mobile.ui.preview.SampleDevices.ultra
import com.magichandy.mobile.ui.preview.SampleDevices.vacuGlide
import com.magichandy.mobile.ui.preview.SampleDevices.vibrator
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Fixed example states that match the v1 design boards, for previews and the debug demo. Every
 * value is synthetic.
 */
object SampleStates {
    /** 2026-11-19 21:40 UTC. */
    private val start = Instant.fromEpochSeconds(1_795_124_400)

    private fun at(minutes: Int, seconds: Int = 0): Instant =
        start + minutes.minutes + seconds.seconds

    private val terra =
        ModelConnection(
            id = ConnectionId("chatgpt"),
            name = "ChatGPT",
            provider = ProviderKind.ChatGpt,
            model = "GPT-6.1 Terra",
            shortName = "Terra",
            capability = CapabilityLevel.Advanced,
            isReasoningOn = true,
            isEncrypted = true,
            status = ConnectionStatus.Ready,
        )

    private val lan =
        ModelConnection(
            id = ConnectionId("lan-ollama"),
            name = "Ollama at 192.168.0.20:11434",
            provider = ProviderKind.Ollama,
            model = "gemma4:12b",
            shortName = "Gemma 12B",
            capability = CapabilityLevel.Intermediate,
            isReasoningOn = false,
            isEncrypted = false,
            status = ConnectionStatus.Ready,
        )

    private val companion =
        PersonaRef(PersonaId("companion"), "Companion", "Warm, attentive and unhurried.")

    private val personas =
        listOf(
            companion,
            PersonaRef(PersonaId("playful"), "Playful", "Light, teasing and quick to laugh."),
            PersonaRef(PersonaId("guide"), "Calm guide", "Slow, steady and reassuring."),
        )

    private val transcript =
        listOf(
            ChatEntry.Message(
                EntryId("m1"),
                at(0),
                Author.You,
                "Slow it down a little and keep the strokes long.",
                null,
            ),
            ChatEntry.Message(
                EntryId("m2"),
                at(0, 20),
                Author.Persona,
                "Easing off. Long, unhurried strokes for a while.",
                CommandReceipt(
                    listOf(
                        ValueChange(ControlKind.Pace, 62, 48),
                        ValueChange(ControlKind.StrokeLow, null, 15),
                        ValueChange(ControlKind.StrokeHigh, null, 85),
                    )
                ),
            ),
            ChatEntry.Message(
                EntryId("m3"),
                at(1),
                Author.You,
                "Perfect. Let Autopilot take over, and build to the finish over twenty minutes.",
                null,
            ),
        )

    private val chat =
        ChatState(
            chatId = ChatId("evening"),
            title = "Evening",
            personaName = companion.name,
            modelName = terra.shortName,
            entries = transcript,
            reply = ReplyState.Streaming("Autopilot has it. I'll build slowly toward the end"),
            chats =
                listOf(
                    ChatSummary(ChatId("evening"), "Evening", companion.name, at(1)),
                    ChatSummary(ChatId("sunday"), "Sunday", "Calm guide", at(-2 * 24 * 60)),
                ),
            sendBlockedBy = null,
        )

    private val limits =
        Limits(topSpeed = Percent(80), strokeLow = Percent(15), strokeHigh = Percent(85))

    /** Chat while playing, with Autopilot on (board V-Chat). */
    val playing =
        AppState(
            firstRun = FirstRunState(hasAcknowledgedAdultsOnly = true),
            chat = chat,
            run =
                RunState(
                    phase = RunPhase.Playing,
                    commandedPace = Percent(48),
                    source = MotionSource.Autopilot,
                    limits = limits,
                    recentChanges =
                        listOf(
                            RunEvent(
                                at(1),
                                MotionSource.Autopilot,
                                ControlKind.Pace,
                                50,
                                56,
                                LimitKind.TopSpeed,
                            )
                        ),
                    isPlanningHeld = false,
                ),
            autopilot =
                AutopilotState(
                    status = AutopilotStatus.Active,
                    nextChangeIn = 40.seconds,
                    finishTarget = FinishTarget(total = 20.minutes, remaining = 12.minutes),
                    changeFrequency = ChangeFrequency(5),
                    checkIns = CheckIns.TalkAndAdjust,
                ),
            devices =
                DevicesState(
                    devices = listOf(handy, vibrator, coyote, ultra, vacuGlide),
                    isBluetoothOn = true,
                    isOnline = true,
                    scan = ScanState.Idle,
                    hasAcknowledgedCoyoteNotes = true,
                ),
            settings =
                SessionSettings(
                    motionMode = MotionMode.Layered,
                    availableModes = listOf(MotionMode.Layered, MotionMode.Simple, MotionMode.Off),
                    style = MotionStyle.Balanced,
                    persona = companion,
                    personas = personas,
                    isMemoryOn = false,
                    memoryEntries = 0,
                ),
            models =
                ModelsState(
                    connections = listOf(terra, lan),
                    chatConnection = terra.id,
                    plannerConnection = null,
                    serverCheck = ServerCheck.Idle,
                    signIn = SignInState.Idle,
                ),
            app =
                AppSettingsState(
                    palette = Palette.SteelAzure,
                    language = AppLanguage.System,
                    versionName = "0.1.0",
                    update = UpdateCheck.NotChecked,
                ),
            stopReceipt = null,
            interruptedRun = null,
            banners = emptyList(),
        )

    private val idleRun =
        playing.run.copy(phase = RunPhase.Idle, commandedPace = null, source = null)
    private val autopilotOff =
        playing.autopilot.copy(status = AutopilotStatus.Off, nextChangeIn = null)

    private fun connected(device: DeviceSummary) =
        device.copy(status = DeviceStatus.Connected, output = null)

    /** Chat at rest: nothing playing and no reply on the way. */
    val idle =
        playing.copy(
            run = idleRun,
            autopilot = autopilotOff,
            chat = chat.copy(reply = ReplyState.Idle),
        )

    /** Chat with no chat model set up yet: the composer says what's missing. */
    val noChatModel = idle.copy(chat = idle.chat.copy(sendBlockedBy = SendBlock.NoChatModel))

    /** Memory on, with three saved details, newest first. */
    val withMemories =
        playing.copy(
            settings =
                playing.settings.copy(
                    isMemoryOn = true,
                    memoryEntries = 3,
                    memories =
                        listOf(
                            "Prefers to be called Sam",
                            "Likes a slow build with long pauses",
                            "Wants a check-in before anything faster",
                        ),
                )
        )

    /** A run with only the Coyote in it (board V-CoyoteLive). */
    val coyotePlaying =
        playing.copy(devices = playing.devices.copy(devices = listOf(SampleDevices.coyotePlaying)))

    /** First launch: nothing set up yet (board V-Onboarding). */
    val firstRun =
        playing.copy(
            firstRun = FirstRunState(hasAcknowledgedAdultsOnly = false),
            run = idleRun,
            autopilot = autopilotOff,
            devices = playing.devices.copy(devices = emptyList()),
            chat = chat.copy(entries = emptyList(), reply = ReplyState.Idle),
        )

    /** After pressing Stop with three devices in the run (board V-StopReceipt). */
    val stopped =
        playing.copy(
            run = idleRun,
            autopilot = autopilotOff,
            chat = chat.copy(reply = ReplyState.Interrupted(InterruptCause.MotionStopped)),
            devices =
                playing.devices.copy(
                    devices =
                        listOf(connected(handy), connected(vibrator), coyote, ultra, vacuGlide)
                ),
            stopReceipt =
                StopReceipt(
                    pressCount = 1,
                    at = at(2),
                    outcomes =
                        listOf(
                            outcome(
                                vacuGlide,
                                StopOutcome.Unconfirmed,
                                cause = UnconfirmedCause.NoReply,
                            ),
                            outcome(handy, StopOutcome.Confirmed, confirmedMillis = 120),
                            // Intiface's reply alone never confirms Stop (system design §2).
                            outcome(
                                vibrator,
                                StopOutcome.Unconfirmed,
                                cause = UnconfirmedCause.BridgeOnly,
                            ),
                        ),
                    didTurnOffAutopilot = true,
                    didCancelReply = true,
                ),
        )

    /** The Handy lost its Bluetooth link mid-run (board V-ConnectionLost). */
    val connectionLost =
        playing.copy(
            run = idleRun,
            autopilot = autopilotOff,
            chat =
                chat.copy(
                    entries =
                        transcript +
                            ChatEntry.Message(
                                EntryId("m4"),
                                at(2),
                                Author.You,
                                "A little faster when it gets to the next part.",
                                null,
                            ) +
                            ChatEntry.MotionStopped(
                                EntryId("m5"),
                                at(2, 10),
                                lostDevice = handy.name,
                            ),
                    reply = ReplyState.Interrupted(InterruptCause.MotionStopped),
                ),
            devices =
                playing.devices.copy(
                    devices =
                        listOf(
                            handy.copy(status = DeviceStatus.Lost, output = null),
                            connected(vibrator),
                            coyote,
                            ultra,
                            vacuGlide,
                        )
                ),
            stopReceipt =
                StopReceipt(
                    pressCount = 1,
                    at = at(2, 10),
                    outcomes =
                        listOf(
                            outcome(
                                handy,
                                StopOutcome.Unconfirmed,
                                cause = UnconfirmedCause.LinkLost,
                            ),
                            // Intiface's reply alone never confirms Stop (system design §2).
                            outcome(
                                vibrator,
                                StopOutcome.Unconfirmed,
                                cause = UnconfirmedCause.BridgeOnly,
                            ),
                        ),
                    didTurnOffAutopilot = true,
                    didCancelReply = true,
                ),
            banners =
                listOf(Banner.DeviceLost(handy.id, handy.name, handy.connection, handy.afterLoss)),
        )

    /** A cold start that found an unresolved run (board V-Restored). */
    val interrupted =
        playing.copy(
            run = idleRun,
            autopilot = autopilotOff,
            interruptedRun =
                InterruptedRun(
                    stoppedAt = at(2),
                    outcomes =
                        listOf(
                            outcome(
                                vacuGlide,
                                StopOutcome.Unconfirmed,
                                cause = UnconfirmedCause.AppStopped,
                            ),
                            outcome(
                                vibrator,
                                StopOutcome.Unconfirmed,
                                cause = UnconfirmedCause.AppStopped,
                            ),
                            outcome(
                                handy,
                                StopOutcome.Unconfirmed,
                                cause = UnconfirmedCause.AppStopped,
                            ),
                        ),
                    wasAutopilotOn = true,
                ),
        )

    private fun outcome(
        device: DeviceSummary,
        outcome: StopOutcome,
        cause: UnconfirmedCause? = null,
        confirmedMillis: Int? = null,
    ) =
        DeviceStopOutcome(
            device = device.id,
            name = device.name,
            family = device.family,
            connection = device.connection,
            outcome = outcome,
            confirmedIn = confirmedMillis?.milliseconds,
            cause = cause,
            afterLoss = device.afterLoss,
        )
}

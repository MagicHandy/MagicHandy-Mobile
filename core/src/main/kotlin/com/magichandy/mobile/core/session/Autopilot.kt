package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.AutopilotStatus
import com.magichandy.mobile.core.api.BackendNotice
import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.ChatSummary
import com.magichandy.mobile.core.api.CheckIns
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.EntryId
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.MotionSource
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.StartRefusal
import com.magichandy.mobile.core.autopilot.Decision
import com.magichandy.mobile.core.autopilot.PlanFacts
import com.magichandy.mobile.core.autopilot.Timing
import com.magichandy.mobile.core.autopilot.decide
import com.magichandy.mobile.core.chat.TurnContext
import com.magichandy.mobile.core.chat.valveAllowed
import com.magichandy.mobile.core.chat.vibration
import com.magichandy.mobile.core.chat.vibratorContext
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.motion.WorkKind
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.PlanningProvider
import com.magichandy.mobile.core.ports.RunIntent
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.ports.activePersona
import com.magichandy.mobile.core.ports.current
import com.magichandy.mobile.core.speakingProvider
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** All scheduling state belongs to the session dispatcher. Planning jobs never write transports. */
internal class Autopilot(
    private val d: CoreDependencies,
    private val engine: MotionEngine,
    private val devices: Devices,
    private val safety: RunSafety,
    private val ui: MutableStateFlow<AppState>,
) {
    private val timing = Timing()
    private val revision = MutableStateFlow(0L)
    private var worker: Job? = null
    private var owner: Epoch? = null
    private var paused = false
    private var standing = false
    val isStanding
        get() = standing

    private var origin = Duration.ZERO
    private var lastChange = Duration.ZERO
    private var holds = 0
    private val speeds = ArrayDeque<Int>()
    private val providerLane = Mutex()
    private val edge = EdgeControl(d, engine, devices, ui)

    fun edge(epoch: Epoch, on: Boolean) {
        edge.set(epoch, on, owner == epoch && !paused) { revision.value++ }
    }

    fun refresh() {
        edge.suspendReturn()
        revision.value++
    }

    fun standingHold(value: Boolean) {
        if (standing != value) {
            standing = value
            refresh()
        }
    }

    suspend fun start(epoch: Epoch) {
        if (
            worker?.isActive == true ||
                epoch != engine.epoch ||
                !ui.value.firstRun.hasAcknowledgedAdultsOnly ||
                isMotionOff()
        )
            return
        if (
            engine.state.value.pausedPosition != null ||
                engine.state.value.journal?.intended == RunIntent.TestMotor
        )
            return
        if (engine.state.value.owners.isEmpty()) {
            devices.refused(StartRefusal.NoDeviceInRun)
            return
        }
        if (model() == null) {
            ui.update { it.copy(backendNotice = BackendNotice.FeatureUnavailable) }
            return
        }
        if (!d.runHost.ensureRunning()) {
            devices.refused(StartRefusal.ServiceUnavailable)
            return
        }
        if (epoch != engine.epoch || isMotionOff() || !safety.autopilot(epoch, true)) return
        if (epoch != engine.epoch || isMotionOff()) {
            safety.autopilot(epoch, false)
            return
        }
        owner = epoch
        paused = false
        standing = false
        origin = d.clock.elapsed()
        lastChange = origin
        holds = 0
        speeds.clear()
        val launched =
            engine.launchWork(epoch, WorkKind.Planner, d.dispatchers.session) {
                revision.collectLatest { serial ->
                    when {
                        owner != epoch || engine.epoch != epoch || isMotionOff() -> {
                            reset()
                            cancel()
                        }
                        paused || engine.state.value.pausedPosition != null ->
                            status(AutopilotStatus.Paused)
                        !reachable() -> status(AutopilotStatus.Held)
                        engine.state.value.edgeRequested ||
                            ui.value.chat.reply is ReplyState.Streaming ->
                            status(AutopilotStatus.Active)
                        else -> drive(epoch, serial)
                    }
                }
            }
        if (!launched.isActive) {
            reset()
            return
        }
        worker = launched
    }

    fun pause(on: Boolean) {
        if (owner == null) return
        paused = on
        refresh()
    }

    suspend fun off() {
        val epoch = owner ?: return
        reset()
        safety.autopilot(epoch, false)
    }

    fun epochChanged(epoch: Epoch) {
        if (owner != null && owner != epoch) reset()
    }

    private fun reset() {
        edge.suspendReturn()
        owner = null
        worker?.cancel()
        worker = null
        paused = false
        standing = false
        status(AutopilotStatus.Off)
    }

    private fun status(value: AutopilotStatus) {
        ui.update { s ->
            val banners = s.banners.filterNot { it == Banner.PlanningHeld }
            s.copy(
                autopilot =
                    s.autopilot.copy(
                        status = value,
                        nextChangeIn = null,
                        finishTarget =
                            s.autopilot.finishTarget?.copy(
                                remaining =
                                    if (value == AutopilotStatus.Off) null
                                    else s.autopilot.finishTarget.remaining
                            ),
                    ),
                run = s.run.copy(isPlanningHeld = value == AutopilotStatus.Held),
                banners =
                    if (value == AutopilotStatus.Held) listOf(Banner.PlanningHeld) + banners
                    else banners,
            )
        }
    }

    private fun model(): SavedModel? = d.models.state.value.let { it.models[it.planner ?: it.chat] }

    private fun reachable(): Boolean {
        val m = model() ?: return false
        val network = d.network.observe().value
        return m.info.status == ConnectionStatus.Ready && m.hasRoute(network)
    }

    private fun isMotionOff(): Boolean =
        ui.value.settings.motionMode == MotionMode.Off ||
            d.settings.state.value.values.mode == MotionMode.Off

    private fun current(epoch: Epoch, serial: Long) =
        owner == epoch &&
            worker?.isActive == true &&
            engine.epoch == epoch &&
            revision.value == serial &&
            !paused &&
            !isMotionOff() &&
            engine.state.value.pausedPosition == null

    private suspend fun drive(epoch: Epoch, serial: Long) = coroutineScope {
        status(AutopilotStatus.Active)
        val speech =
            if (ui.value.autopilot.checkIns != CheckIns.Off) launch { speak(epoch, serial) }
            else null
        try {
            var deadline = d.clock.elapsed()
            var retryDelay = 2.seconds
            while (current(epoch, serial)) {
                if (standing) {
                    awaitCancellation()
                }
                val clock = launch { countdown(epoch, deadline) }
                val previous = engine.state.value.score
                val step = decision("plan")
                if (!current(epoch, serial)) {
                    clock.cancel()
                    break
                }
                if (step == null) {
                    clock.cancel()
                    status(AutopilotStatus.Held)
                    delay(retryDelay)
                    retryDelay = minOf(30.seconds, retryDelay * 2)
                    continue
                }
                if (ui.value.autopilot.status == AutopilotStatus.Held)
                    status(AutopilotStatus.Active)
                retryDelay = 2.seconds
                clock.join()
                if (!current(epoch, serial)) break
                if (previous !== engine.state.value.score) continue
                if (!apply(epoch, serial, step)) {
                    if (current(epoch, serial)) status(AutopilotStatus.Held)
                    break
                }
                deadline =
                    d.clock.elapsed() +
                        timing.motion(ui.value.autopilot.changeFrequency, step.next, step.seconds)
            }
        } finally {
            speech?.cancel()
        }
    }

    private suspend fun countdown(epoch: Epoch, deadline: Duration) {
        while (owner == epoch && engine.epoch == epoch && !isMotionOff()) {
            val now = d.clock.elapsed()
            val left = (deadline - now).coerceAtLeast(Duration.ZERO)
            ui.update { s ->
                if (owner != epoch || engine.epoch != epoch || isMotionOff()) return@update s
                s.copy(
                    autopilot =
                        s.autopilot.copy(
                            nextChangeIn = left,
                            finishTarget =
                                s.autopilot.finishTarget?.let {
                                    it.copy(
                                        remaining =
                                            (it.total - (now - origin)).coerceAtLeast(Duration.ZERO)
                                    )
                                },
                        )
                )
            }
            if (left == Duration.ZERO) return
            delay(minOf(1.seconds, left))
        }
    }

    private suspend fun speak(epoch: Epoch, serial: Long) {
        var next = "normal"
        while (current(epoch, serial)) {
            delay(timing.speech(next))
            if (ui.value.autopilot.status == AutopilotStatus.Held) continue
            val type = if (ui.value.autopilot.checkIns == CheckIns.Talk) "talk" else "speech"
            val persona = d.settings.state.value.activePersona
            val step = decision(type, persona) ?: continue
            if (!current(epoch, serial)) return
            if (type == "speech" && !apply(epoch, serial, step)) continue
            next = step.next
            val text = step.reply.takeIf { it.isNotBlank() } ?: continue
            val chat =
                ui.value.chat.chatId
                    ?: ChatId(java.util.UUID.randomUUID().toString()).also {
                        d.chats.create(ChatSummary(it, "Autopilot", persona.name, d.clock.now()))
                    }
            val entry =
                ChatEntry.Message(
                    EntryId(java.util.UUID.randomUUID().toString()),
                    d.clock.now(),
                    Author.Persona,
                    text,
                    null,
                    speaker = persona.name,
                )
            d.chats.append(chat, entry, persona.id, emptyList()) { current(epoch, serial) }
        }
    }

    private suspend fun apply(epoch: Epoch, serial: Long, step: Decision): Boolean {
        if (!current(epoch, serial)) return false
        if (step.tempo != null || step.air != 0)
            return devices.playback.applyTempo(epoch, step.tempo, step.air, MotionSource.Autopilot)
        val score = step.score
        if (score == null || score == engine.state.value.score?.score) {
            holds++
            return step.vibration?.let {
                devices.playback.applyVibration(epoch, it, MotionSource.Autopilot)
            } ?: true
        }
        if (
            !devices.playback.applyScore(epoch, score, MotionSource.Autopilot, true) {
                current(epoch, serial)
            }
        )
            return false
        holds = 0
        lastChange = d.clock.elapsed()
        step.vibration?.let {
            if (!devices.playback.applyVibration(epoch, it, MotionSource.Autopilot)) return false
        }
        speeds.addLast(score.speed)
        if (speeds.size > 12) speeds.removeFirst()
        return true
    }

    private suspend fun decision(
        purpose: String,
        persona: PersonaRef = d.settings.state.value.activePersona,
    ): Decision? {
        val m = model() ?: return null
        val peer = if (purpose == "plan") d.provider(m) else d.speakingProvider(m, persona)
        val p = peer as? PlanningProvider ?: return null
        val state = ui.value
        val preferences = state.settings
        val e = engine.state.value
        val cap = state.run.limits.topSpeed.value
        val shared = d.models.state.value.chat == m.info.id
        val c =
            TurnContext(
                preferences.motionMode,
                m.info.capability,
                state.app.language,
                m.info.isReasoningOn,
                e.members.isNotEmpty(),
                e.pausedPosition != null,
                e.score?.score,
                minOf(10, cap).coerceAtLeast(1),
                cap,
                autopilot = true,
                standingHold = standing,
                envelope = devices.playback.planningEnvelope(e.members),
                local = m.info.provider in ChatProposal.localProviders,
                vibrator = state.vibratorContext(),
                tempo = e.tempo?.plan?.score,
                previousTempo = e.previousTempo,
                airAllowed = state.valveAllowed(),
                airBalance = e.airBalance,
                tempoLevel = state.run.commandedPace?.value ?: 0,
                style = preferences.style,
                persona = persona,
                memories =
                    if (shared && preferences.isMemoryOn)
                        d.chats.state.value.memory[persona.id].orEmpty()
                    else emptyList(),
            )
        val now = d.clock.elapsed()
        val facts =
            PlanFacts(
                now - origin,
                now - lastChange,
                holds,
                speeds.toList(),
                state.autopilot.finishTarget?.total,
                timing.floor(state.autopilot.changeFrequency),
                purpose,
            )
        val history =
            if (!shared) emptyList()
            else
                state.chat.entries.filterIsInstance<ChatEntry.Message>().takeLast(16).map {
                    TextMessage(
                        if (it.author == Author.You) TextRole.User else TextRole.Assistant,
                        it.text,
                    )
                }
        return try {
            withContext(d.dispatchers.io) {
                providerLane.withLock { decide(p, m, c, facts, history) }
            }
        } catch (_: java.io.IOException) {
            null
        }
    }
}

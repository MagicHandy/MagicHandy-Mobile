package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.AutopilotStatus
import com.magichandy.mobile.core.api.BackendNotice
import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.CheckIns
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.MotionSource
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.StartRefusal
import com.magichandy.mobile.core.autopilot.Decision
import com.magichandy.mobile.core.autopilot.PlanFacts
import com.magichandy.mobile.core.autopilot.Planned
import com.magichandy.mobile.core.autopilot.Timing
import com.magichandy.mobile.core.chat.vibration
import com.magichandy.mobile.core.motion.EngineState
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.motion.WorkKind
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.RunIntent
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.activePersona
import com.magichandy.mobile.core.ports.current
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    private data class HeldFailure(val model: SavedModel, val reason: ProviderReplyFailure)

    private var heldFailure: HeldFailure? = null
    private var paused = false
    private var standing = false
    val isStanding
        get() = standing

    private var origin = Duration.ZERO
    private var lastChange = Duration.ZERO
    private var nextChangeAt = Duration.ZERO
    private var holds = 0
    private val speeds = ArrayDeque<Int>()
    private val turns = AutopilotTurns(d, engine, devices, ui)
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
        if (!devices.playback.hasRunRoster()) {
            devices.refused(StartRefusal.NoDeviceInRun)
            return
        }
        if (model() == null) {
            ui.update { it.copy(backendNotice = BackendNotice.FeatureUnavailable) }
            return
        }
        val running =
            try {
                d.runHost.ensureRunning()
            } catch (_: IOException) {
                DiagnosticReporter.record(DiagnosticKind.ServiceUnavailable)
                false
            }
        if (!running) {
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
        nextChangeAt = origin
        holds = 0
        speeds.clear()
        val launched =
            engine.launchWork(epoch, WorkKind.Planner, d.dispatchers.session) {
                revision.collectLatest { serial ->
                    val held = heldFailure?.takeIf { it.model == model() }
                    heldFailure = held
                    when {
                        owner != epoch || engine.epoch != epoch || isMotionOff() -> {
                            reset()
                            cancel()
                        }
                        paused || engine.state.value.pausedPosition != null ->
                            status(AutopilotStatus.Paused)
                        held != null -> status(AutopilotStatus.Held, held.reason)
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
        if (!on) heldFailure = null
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
        heldFailure = null
        paused = false
        standing = false
        status(AutopilotStatus.Off)
    }

    private fun holdFailure(failure: ProviderReplyFailure?, requested: SavedModel?): Boolean {
        if (failure == null || !failure.needsFix || requested == null || requested != model())
            return false
        heldFailure = HeldFailure(requested, failure)
        status(AutopilotStatus.Held, failure)
        refresh()
        return true
    }

    /** A successful sign-in can replace a credential without changing its catalog row. */
    fun signedIn(id: ConnectionId) {
        if (heldFailure?.model?.info?.id != id) return
        heldFailure = null
        refresh()
    }

    private fun status(value: AutopilotStatus, failure: ProviderReplyFailure? = null) {
        ui.update { s ->
            val banners =
                s.banners.filterNot { it == Banner.PlanningHeld || it is Banner.PlanningFailed }
            val held =
                when {
                    value != AutopilotStatus.Held -> null
                    failure != null -> Banner.PlanningFailed(failure)
                    else -> Banner.PlanningHeld
                }
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
                banners = listOfNotNull(held) + banners,
            )
        }
    }

    private fun model(): SavedModel? = d.models.state.value.let { it.models[it.planner ?: it.chat] }

    private fun reachable(): Boolean {
        val m = model() ?: return false
        val network = d.network.observe().value
        return m.info.status == ConnectionStatus.Ready && m.hasRoute(network)
    }

    /** Give an accepted chat target a phrase of its own before applying an automatic change. */
    fun chatChangedMotion() {
        if (owner == null) return
        lastChange = d.clock.elapsed()
        holds = 0
        nextChangeAt =
            lastChange + timing.motion(ui.value.autopilot.changeFrequency, "normal", null)
        refresh()
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
            var retryDelay = 2.seconds
            while (current(epoch, serial)) {
                if (standing) {
                    awaitCancellation()
                }
                val deadline = nextChangeAt
                val clock = launch { countdown(epoch, deadline) }
                val before = engine.state.value
                val requestedModel = model()
                val planned = decision("plan")
                if (!current(epoch, serial) || model() != requestedModel) {
                    clock.cancel()
                    break
                }
                val step = (planned as? Planned.Step)?.decision
                if (step == null) {
                    clock.cancel()
                    val failure = (planned as? Planned.Failed)?.failure
                    if (holdFailure(failure, requestedModel)) break
                    status(
                        AutopilotStatus.Held,
                        failure?.takeIf { it.reason == ProviderReplyFailure.Reason.RateLimited },
                    )
                    delay(retryDelay)
                    retryDelay = minOf(30.seconds, retryDelay * 2)
                    continue
                }
                if (ui.value.autopilot.status == AutopilotStatus.Held)
                    status(AutopilotStatus.Active)
                retryDelay = 2.seconds
                clock.join()
                if (!current(epoch, serial)) break
                if (!before.sameMotion(engine.state.value)) continue
                if (!apply(epoch, serial, step)) {
                    if (current(epoch, serial)) {
                        if (!devices.playback.hasRunRoster())
                            devices.refused(StartRefusal.NoDeviceInRun)
                        pause(true)
                    }
                    break
                }
                nextChangeAt =
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
            val requestedModel = model()
            val before = engine.state.value
            val answer = decision(type, persona)
            if (!current(epoch, serial) || model() != requestedModel) return
            if (answer is Planned.Failed && holdFailure(answer.failure, requestedModel)) return
            val step = (answer as? Planned.Step)?.decision ?: continue
            if (
                type == "speech" &&
                    (!before.sameMotion(engine.state.value) || !apply(epoch, serial, step))
            )
                continue
            next = step.next
            val text = step.reply.takeIf { it.isNotBlank() } ?: continue
            turns.saveCheckIn(text, persona) { current(epoch, serial) }
        }
    }

    private suspend fun apply(epoch: Epoch, serial: Long, step: Decision): Boolean {
        if (!current(epoch, serial)) return false
        if (step.tempo != null || step.air != 0) {
            val before = engine.state.value
            if (!devices.playback.applyTempo(epoch, step.tempo, step.air, MotionSource.Autopilot))
                return false
            val after = engine.state.value
            if (
                before.tempo?.plan?.score != after.tempo?.plan?.score ||
                    before.airBalance != after.airBalance
            ) {
                holds = 0
                lastChange = d.clock.elapsed()
            } else holds++
            return true
        }
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
    ): Planned {
        val selected = model() ?: return Planned.Missed
        val now = d.clock.elapsed()
        val facts =
            PlanFacts(
                now - origin,
                now - lastChange,
                holds,
                speeds.toList(),
                ui.value.autopilot.finishTarget?.total,
                timing.floor(ui.value.autopilot.changeFrequency),
                purpose,
            )
        return turns.request(selected, persona, facts, standing)
    }
}

private fun EngineState.sameMotion(other: EngineState): Boolean =
    score === other.score && tempo?.plan === other.tempo?.plan && airBalance == other.airBalance

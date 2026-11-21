// Ported from internal/deviceapp/lifecycle.go and internal/httpapi/playback_devices.go at 1b80d72e.
package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.BackendNotice
import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceOutput
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.MotionSource
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StartRefusal
import com.magichandy.mobile.core.motion.HandyModel
import com.magichandy.mobile.core.motion.ManualPlayback
import com.magichandy.mobile.core.motion.ManualTarget
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.motion.PlaybackPosition
import com.magichandy.mobile.core.motion.PlaybackStream
import com.magichandy.mobile.core.motion.ScoreRequest
import com.magichandy.mobile.core.motion.compileScore
import com.magichandy.mobile.core.motion.travelRate
import com.magichandy.mobile.core.ports.CoyoteControls
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.RunIntent
import com.magichandy.mobile.core.score.SemanticScore
import com.magichandy.mobile.core.score.TempoScore
import com.magichandy.mobile.core.score.TempoShape
import com.magichandy.mobile.core.score.VibrationEnvelope
import com.magichandy.mobile.core.score.VibrationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Binds semantic targets to the admitted roster and owns its output timeline. */
internal class DevicePlayback(
    private val d: CoreDependencies,
    private val engine: MotionEngine,
    private val safety: RunSafety,
    private val ui: MutableStateFlow<AppState>,
    private val lead: () -> DeviceId?,
    private val refused: (StartRefusal) -> Unit,
) {
    private val playback = ManualPlayback(d, engine)

    suspend fun applyScore(
        epoch: Epoch,
        score: SemanticScore,
        source: MotionSource,
        canStart: Boolean,
        admitted: () -> Boolean = { true },
    ): Boolean {
        val before = engine.state.value
        if (
            before.epoch != epoch ||
                before.pausedPosition != null ||
                !ui.value.firstRun.hasAcknowledgedAdultsOnly ||
                isMotionOff()
        )
            return false
        val ids =
            if (before.members.isNotEmpty()) before.members
            else
                ui.value.devices.devices
                    .filter {
                        it.status == DeviceStatus.Connected &&
                            it.membership != RunMembership.NotInRun
                    }
                    .map { it.id }
                    .toSet()
        if (ids.isEmpty() || before.members.isEmpty() && !canStart) return false
        val model = calibration(ids)
        val plan = withContext(d.dispatchers.io) { compileScore(score, model) } ?: return false
        val current = engine.state.value
        if (
            !admitted() ||
                isMotionOff() ||
                current.streamGeneration != before.streamGeneration ||
                current.score !== before.score ||
                current.epoch != epoch ||
                current.members != before.members ||
                current.owners != before.owners
        )
            return false
        val request = ScoreRequest(score, source, plan)
        if (before.members.isNotEmpty())
            return before.journal?.let { engine.selectScore(epoch, it, request) } == true
        if (engine.state.value.members.isNotEmpty()) return false
        start(epoch, null, request)
        return engine.state.value.score === request
    }

    suspend fun applyVibration(
        epoch: Epoch,
        envelope: VibrationEnvelope,
        source: MotionSource,
    ): Boolean {
        val s = engine.state.value
        if (
            s.epoch != epoch ||
                s.pausedPosition != null ||
                !ui.value.firstRun.hasAcknowledgedAdultsOnly ||
                isMotionOff()
        )
            return false
        if (s.members.isNotEmpty())
            return s.members.any { s.owners[it]?.device?.family == DeviceFamily.Vibrator } &&
                engine.vibration(epoch, envelope)
        if (
            envelope.mode in
                setOf(
                    VibrationMode.Follow,
                    VibrationMode.Off,
                )
        )
            return true
        val roster =
            ui.value.devices.devices
                .filter {
                    it.family == DeviceFamily.Vibrator &&
                        it.membership == RunMembership.NextRun &&
                        it.status == DeviceStatus.Connected
                }
                .map { it.id }
                .toSet()
        if (roster.isEmpty()) return false
        start(
            epoch,
            null,
            only = roster,
            vibration = envelope,
            automatic = source == MotionSource.Autopilot,
        )
        return engine.state.value.vibration?.envelope == envelope
    }

    suspend fun applyTempo(
        epoch: Epoch,
        score: TempoScore?,
        air: Int,
        source: MotionSource,
    ): Boolean {
        val s = engine.state.value
        if (
            epoch != s.epoch ||
                s.pausedPosition != null ||
                ui.value.settings.motionMode != MotionMode.Tempo ||
                isMotionOff()
        )
            return false
        val rows =
            ui.value.devices.devices.filter {
                it.family == DeviceFamily.VacuGlide &&
                    if (s.members.isEmpty())
                        it.status == DeviceStatus.Connected &&
                            it.membership == RunMembership.NextRun
                    else it.id in s.members
            }
        if (
            rows.isEmpty() ||
                air != 0 &&
                    (s.members.isEmpty() ||
                        rows.any { !(it.settings as DeviceSettings.VacuGlide).mayStepValve })
        )
            return false
        if (s.members.isNotEmpty()) return engine.tempo(epoch, score, air, source)
        if (score == null || score.sections.all { it.shape == TempoShape.Pause }) return true
        start(
            epoch,
            null,
            only = rows.map { it.id }.toSet(),
            automatic = source == MotionSource.Autopilot,
            tempo = score,
            tempoSource = source,
        )
        return engine.state.value.tempo?.plan?.score == score
    }

    private fun calibration(ids: Set<DeviceId>) =
        HandyModel.calibration(
            ui.value.devices.devices
                .filter { it.id in ids }
                .mapNotNull { (it.settings as? DeviceSettings.Handy)?.model }
        )

    /** The model sees the neutral coordinate range and the calibrated profile ceiling. */
    fun planningEnvelope(members: Set<DeviceId>): JsonObject? {
        if (members.isEmpty()) return null
        val peak = kotlin.math.round(calibration(members).travelRate(100) * 10) / 10
        return buildJsonObject {
            put("position_min_percent", 0)
            put("position_max_percent", 100)
            if (peak == peak.toInt().toDouble())
                put("profile_peak_velocity_percent_per_second", peak.toInt())
            else put("profile_peak_velocity_percent_per_second", peak)
        }
    }

    suspend fun start(
        epoch: Epoch,
        test: DeviceId?,
        request: ScoreRequest? = null,
        only: Set<DeviceId>? = null,
        vibration: VibrationEnvelope? = null,
        automatic: Boolean = false,
        tempo: TempoScore? = null,
        tempoSource: MotionSource = MotionSource.You,
    ) {
        if (!ui.value.firstRun.hasAcknowledgedAdultsOnly) return
        if (isMotionOff() && test == null) {
            refused(StartRefusal.MotionModeOff)
            return
        }
        if (engine.state.value.members.isNotEmpty()) {
            ui.update { it.copy(backendNotice = BackendNotice.RunAlreadyActive) }
            return
        }
        val state = ui.value
        val ids =
            state.devices.devices
                .filter {
                    it.status == DeviceStatus.Connected &&
                        (only == null || it.id in only) &&
                        (test != null ||
                            state.settings.motionMode != MotionMode.Tempo ||
                            it.family == DeviceFamily.VacuGlide) &&
                        if (test != null) it.id == test else it.membership != RunMembership.NotInRun
                }
                .map { it.id }
        val leadId = lead()?.takeIf { it in ids } ?: ids.firstOrNull()
        val owners = ids.mapNotNull(engine.state.value.owners::get)
        when (
            val start =
                safety.start(
                    epoch,
                    owners,
                    if (test != null) RunIntent.TestMotor else RunIntent.Start,
                    autopilot = automatic || request?.source == MotionSource.Autopilot,
                )
        ) {
            is StartDecision.Refused -> refused(start.cause)
            StartDecision.Superseded ->
                if (
                    engine.epoch == epoch &&
                        (!engine.state.value.canStart ||
                            engine.state.value.journal?.devices.orEmpty().any {
                                safety.awaitsReturn(it.id)
                            })
                )
                    ui.update { it.copy(backendNotice = BackendNotice.ReconcileDevices) }
            StartDecision.Admitted -> {
                ui.update { it.copy(run = it.run.copy(recentChanges = emptyList())) }
                if (vibration != null && !engine.vibration(epoch, vibration)) return
                if (tempo != null || owners.all { it.device.family == DeviceFamily.VacuGlide }) {
                    if (
                        !engine.tempo(
                            epoch,
                            tempo ?: TempoScore.steady(if (test != null) 10 else 25),
                            0,
                            tempoSource,
                        )
                    )
                        return
                }
                if (request != null) {
                    val journal = engine.state.value.journal ?: return
                    if (!engine.selectScore(epoch, journal, request)) return
                }
                play(epoch, owners, leadId, test != null, PlaybackPosition())
            }
        }
    }

    suspend fun pause(expected: Epoch) {
        val current = engine.state.value
        if (current.epoch != expected || current.members.isEmpty()) return
        if (current.journal?.intended == RunIntent.TestMotor) {
            engine.stop(null, expected)
        } else safety.pause(expected, playback.position(engine.stream(expected)))
    }

    suspend fun resume(expected: Epoch) {
        val state = engine.state.value
        val position = state.pausedPosition ?: return
        if (state.epoch != expected) return
        val owners = state.members.mapNotNull(state.owners::get)
        when (val decision = safety.resume(expected)) {
            is StartDecision.Refused -> refused(decision.cause)
            StartDecision.Superseded -> Unit
            StartDecision.Admitted -> play(expected, owners, lead(), false, position)
        }
    }

    private fun play(
        epoch: Epoch,
        owners: List<DeviceTransport>,
        leadId: DeviceId?,
        test: Boolean,
        position: PlaybackPosition,
    ) {
        val ids = owners.map { it.device.id }.toSet()
        val stream = engine.stream(epoch)
        playback.play(stream, owners, calibration(ids), test, position) { batch, target ->
            showPlaying(
                stream,
                ids,
                leadId?.takeIf { it in ids } ?: ids.firstOrNull(),
                batch,
                target,
            )
        }
    }

    private fun showPlaying(
        stream: PlaybackStream,
        ids: Set<DeviceId>,
        leadId: DeviceId?,
        batch: MotionBatch,
        target: ManualTarget,
    ) {
        ui.update { state ->
            if (!engine.state.value.plays(stream)) return@update state
            val rows =
                state.devices.devices.map { row ->
                    if (row.id !in ids) row
                    else
                        row.copy(
                            status = DeviceStatus.Playing,
                            membership = RunMembership.InRun(row.id == leadId),
                            output = output(row, batch, target),
                        )
                }
            val leadRow = rows.singleOrNull { it.id == leadId }
            val tempoLead = leadRow?.family == DeviceFamily.VacuGlide
            val commandedPace =
                if (tempoLead) (leadRow.output as? DeviceOutput.Tempo)?.tempo else batch.pace
            state.copy(
                run =
                    state.run.copy(
                        phase = if (target.holding) RunPhase.Edging else RunPhase.Playing,
                        source =
                            if (tempoLead) engine.state.value.tempo?.source ?: target.source
                            else target.source,
                        commandedPace = commandedPace,
                        recentChanges = state.run.recordChanges(d.clock.now(), target, batch),
                    ),
                devices = state.devices.copy(devices = rows),
                banners = state.banners.filterNot { it is Banner.StartRefused },
            )
        }
    }

    private fun output(
        row: DeviceSummary,
        batch: MotionBatch,
        target: ManualTarget,
    ): DeviceOutput? =
        when (val config = row.settings) {
            is DeviceSettings.Handy,
            is DeviceSettings.LinearActuator,
            DeviceSettings.AutoblowUltra ->
                DeviceOutput.Stroke(
                    batch.pace,
                    Percent(target.requested).takeIf {
                        it.value != batch.pace.value && !target.holding
                    },
                    target.physical(target.band.first, batch.limits),
                    target.physical(target.band.last, batch.limits),
                )
            is DeviceSettings.Vibrator ->
                engine.state.value.owners[row.id]?.feedback?.value
                    ?: DeviceOutput.Level(
                        Percent(minOf(batch.pace.value, config.maxLevel.value)),
                        config.maxLevel,
                        true,
                    )
            is DeviceSettings.Coyote ->
                (engine.state.value.owners[row.id] as? CoyoteControls)?.reported?.value
            is DeviceSettings.VacuGlide -> engine.state.value.owners[row.id]?.feedback?.value
        }

    private fun isMotionOff(): Boolean =
        ui.value.settings.motionMode == MotionMode.Off ||
            d.settings.state.value.values.mode == MotionMode.Off
}

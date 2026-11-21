// Ported from internal/modes/edge.go at 1b80d72e.
package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.MotionSource
import com.magichandy.mobile.core.autopilot.edgePlan
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.PlanningProvider
import com.magichandy.mobile.core.ports.current
import com.magichandy.mobile.core.score.LayeredScore
import com.magichandy.mobile.core.score.TempoScore
import com.magichandy.mobile.core.score.atSpeed
import com.magichandy.mobile.core.score.holding
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class EdgeControl(
    private val d: CoreDependencies,
    private val engine: MotionEngine,
    private val devices: Devices,
    private val ui: StateFlow<AppState>,
) {
    private var returnJob: Job? = null

    fun suspendReturn() {
        returnJob?.cancel()
        returnJob = null
    }

    fun set(epoch: Epoch, on: Boolean, automatic: Boolean, changed: () -> Unit) {
        suspendReturn()
        val before = engine.state.value
        val model = d.models.state.value.let { it.models[it.planner ?: it.chat] }
        val native = before.tempo
        val previous =
            if (native == null) before.score?.score?.speed ?: ui.value.run.commandedPace?.value ?: 0
            else
                native.plan
                    .level((d.clock.elapsed() - native.since).inWholeMilliseconds)
                    .roundToInt()
        engine.setEdge(epoch, on)
        changed()
        if (
            !on ||
                before.edgeRequested ||
                !automatic ||
                !engine.state.value.edgeRequested ||
                model == null ||
                previous < 1
        )
            return
        val provider = d.provider(model) as? PlanningProvider ?: return
        val limit =
            if (native != null) previous else minOf(previous, ui.value.run.limits.topSpeed.value)
        val holding = (previous * 0.4).roundToInt().coerceIn(1, 20)
        fun current(): Boolean =
            engine.state.value.let {
                it.epoch == epoch &&
                    it.journal?.runId == before.journal?.runId &&
                    it.edgeRequested &&
                    it.pausedPosition == null &&
                    it.streamGeneration == before.streamGeneration
            }
        returnJob =
            engine.launchWork(
                epoch,
                com.magichandy.mobile.core.motion.WorkKind.Planner,
                d.dispatchers.session,
            ) {
                val plan =
                    try {
                        withTimeoutOrNull(25.seconds) {
                            withContext(d.dispatchers.io) {
                                edgePlan(provider, previous, holding, limit, native != null)
                            }
                        }
                    } catch (_: java.io.IOException) {
                        null
                    } ?: return@launchWork
                delay(plan.seconds.seconds)
                if (!current()) return@launchWork
                if (native != null) {
                    engine.setEdge(epoch, false)
                    if (plan.gentle)
                        devices.playback.applyTempo(
                            epoch,
                            TempoScore.steady(plan.speed),
                            0,
                            MotionSource.Autopilot,
                        )
                } else {
                    val saved =
                        before.score?.score
                            ?: LayeredScore(
                                low = 0,
                                high = 100,
                                speed = previous,
                                floor = 100,
                                ceiling = 100,
                                layers = emptyList(),
                            )
                    val score =
                        (if (plan.gentle) saved.holding(previous) else saved).atSpeed(plan.speed)
                    if (
                        !devices.playback.applyScore(
                            epoch,
                            score,
                            MotionSource.Autopilot,
                            false,
                            ::current,
                        )
                    )
                        return@launchWork
                }
                changed()
            }
    }
}

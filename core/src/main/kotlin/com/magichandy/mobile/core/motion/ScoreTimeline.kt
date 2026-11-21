// Ported from internal/motion/{flow_shape_transition,gesture_handoff,transition}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.MotionSource
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.score.SemanticScore
import com.magichandy.mobile.core.score.atSpeed
import com.magichandy.mobile.core.score.holding
import kotlin.math.abs
import kotlin.math.ceil

internal data class ScoreRequest(
    val score: SemanticScore,
    val source: MotionSource,
    val prepared: ContinuousPlan,
)

internal data class PathSection(val start: Long, val path: ContinuousPath, val offset: Long)

internal data class FrozenScore(val sections: List<PathSection>, val score: SemanticScore)

/** A bounded set of already submitted or upcoming path sections, owned by the output lane. */
internal class ScoreTimeline(
    pace: Int,
    private val model: HandyModel,
    position: PlaybackPosition,
) : ContinuousPath {
    private val manual = ManualTimeline(pace, model, position)
    @Volatile
    private var sections: List<PathSection> =
        position.frozen?.sections ?: listOf(PathSection(0, manual, 0))
    @Volatile private var selected: SemanticScore? = position.frozen?.score

    override val maximumVelocity = model.travelRate(100)
    override val accelerationBound
        get() = sections.maxOf { it.path.accelerationBound }

    private fun section(at: Long) = sections.lastOrNull { it.start <= at } ?: sections.first()

    fun bufferedSampler(devices: List<DeviceRef>): BufferedSampler {
        val only = devices.singleOrNull()
        return BufferedSampler(
            this,
            model == HandyModel.Original &&
                only?.family == DeviceFamily.Handy &&
                only.connection == ConnectionKind.HandyCloud,
        )
    }

    override fun sparsePositionTolerance(start: Long, end: Long): Double {
        val active = section(start)
        if (
            sections.any { it.start > start && it.start < end } ||
                active.start == start && start != 0L
        )
            return precisePositionTolerance
        return active.path.sparsePositionTolerance(start + active.offset, end + active.offset)
    }

    override fun accelerationBoundBetween(start: Long, end: Long): Double {
        val paths = sections
        return paths.indices
            .filter {
                paths[it].start <= end && (paths.getOrNull(it + 1)?.start ?: Long.MAX_VALUE) > start
            }
            .maxOfOrNull {
                paths[it]
                    .path
                    .accelerationBoundBetween(start + paths[it].offset, end + paths[it].offset)
            } ?: 7500.0
    }

    override fun stateAt(at: Long): CurveState = section(at).let { it.path.stateAt(at + it.offset) }

    override fun legPeakSpeed(at: Long): Double =
        section(at).let { it.path.legPeakSpeed(at + it.offset) }

    override fun sampleAt(timeMillis: Long): CurvePoint {
        val s = section(timeMillis)
        return s.path.sampleAt(timeMillis + s.offset).copy(timeMillis = timeMillis)
    }

    override fun knotTimes(start: Long, end: Long): Set<Long> = buildSet {
        val path = sections
        for ((i, s) in path.withIndex()) {
            val lo = maxOf(start, s.start)
            val hi = minOf(end, path.getOrNull(i + 1)?.start ?: end)
            if (lo >= hi) continue
            if (s.start in start until end) add(s.start)
            for (at in s.path.knotTimes(lo + s.offset, hi + s.offset)) add(at - s.offset)
        }
    }

    fun positionAt(millis: Long): PlaybackPosition {
        val score = selected ?: return manual.positionAt(millis)
        val path = sections
        val index = path.indexOfLast { it.start <= millis }.coerceAtLeast(0)
        val frozen =
            path.drop(index).map {
                it.copy(start = maxOf(0, it.start - millis), offset = it.offset + millis)
            }
        return PlaybackPosition(band = score.low..score.high, frozen = FrozenScore(frozen, score))
    }

    fun manual(pace: Int, unsent: Long, played: Long, band: IntRange) =
        manual.retarget(pace, unsent, played, band)

    fun update(
        state: EngineState,
        limits: Limits,
        test: Boolean,
        unsent: Long,
        played: Long,
    ): ManualTarget? {
        val request = state.score
        if (request == null || test) {
            val target = ManualTarget.from(limits, test, state.edgeRequested)
            if (target.pace.value == 0) return null
            manual(target.pace.value, unsent, played, target.band)
            return target
        }
        val speed = minOf(request.score.speed, limits.topSpeed.value)
        if (speed == 0) return null
        val desired =
            if (state.edgeRequested) request.score.holding(speed) else request.score.atSpeed(speed)
        if (desired != selected) {
            val plan =
                if (desired == request.score) request.prepared
                else compileScore(desired, model) ?: return null
            if (!install(desired, plan, unsent, played)) return null
        }
        return ManualTarget(
            Percent(desired.speed),
            desired.low..desired.high,
            state.edgeRequested,
            request.source,
            request.score.speed,
        )
    }

    fun install(score: SemanticScore, plan: ContinuousPlan, unsent: Long, played: Long): Boolean {
        if (score == selected) return true
        if (unsent == 0L && selected == null) {
            sections = listOf(PathSection(0, plan, 0))
            selected = score
            return true
        }
        val turns = knotTimes(unsent, unsent + 60_000).sorted().take(3)
        for (turn in turns) {
            val direction = sampleAt(turn + 5).position - sampleAt(turn).position
            if (direction == 0.0) continue
            // Keep queued sections intact. Use the first feasible point on the outgoing leg.
            for (delay in listOf(0L, 1L, 25L, 50L, 100L)) {
                val at = turn + delay
                val current = section(at)
                val state = stateAt(at)
                if (state.velocity * direction < -1e-9) continue
                val phase =
                    when (val path = current.path) {
                        is ContinuousPlan -> path.phaseAt(at + current.offset)
                        is ManualTimeline -> path.positionAt(at + current.offset).phase
                        else -> 0.0
                    }
                val join = join(plan, state, direction, at, phase) ?: continue
                val previous = sections
                val fromIndex = previous.indexOfLast { it.start <= played }.coerceAtLeast(0)
                val upto = previous.indexOf(current)
                sections =
                    previous.subList(minOf(fromIndex, upto), upto + 1).filter { it.start < at } +
                        join
                selected = score
                return true
            }
        }
        return false
    }

    private fun join(
        plan: ContinuousPlan,
        from: CurveState,
        direction: Double,
        at: Long,
        phase: Double,
    ): List<PathSection>? {
        val turns = plan.knotTimes(0, plan.periodMillis).sorted()
        val cycleTime = phase * plan.periodMillis
        val slot = turns.indexOfLast { it <= cycleTime }.coerceAtLeast(0)
        val candidates =
            (listOf(0, 1, -1, 2, -2, 3, -3, 4, -4))
                .map { Math.floorMod(slot + it, turns.size) }
                .distinct()
        for (i in candidates) {
            val first = turns[i]
            val end = turns[(i + 1) % turns.size]
            val left = plan.stateAt(first)
            val right = plan.stateAt(end)
            if (
                (right.position - left.position) * direction <= 0 ||
                    (right.position - from.position) * direction <= 0
            )
                continue
            if (
                abs(left.position - from.position) < 1e-7 &&
                    abs(left.velocity - from.velocity) < 1e-9 &&
                    abs(left.acceleration - from.acceleration) < 1e-10
            )
                return listOf(PathSection(at, plan, first - at))
            val duration = if (end > first) end - first else end + plan.periodMillis - first
            val preferred =
                maxOf(
                    100L,
                    ceil(
                            duration * abs(right.position - from.position) /
                                abs(right.position - left.position)
                        )
                        .toLong(),
                )
            val ceiling =
                minOf(
                    maximumVelocity,
                    maxOf(
                        legPeakSpeed(at),
                        plan.legPeakSpeed(first + 1),
                        abs(from.velocity) * 1000,
                    ),
                )
            val bridge = TurnBridge.fit(from, right, preferred, ceiling) ?: continue
            val arrival = at + bridge.duration
            return listOf(PathSection(at, bridge, -at), PathSection(arrival, plan, end - arrival))
        }
        return null
    }
}

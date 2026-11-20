// Ported from internal/motion/{flow_shape_transition,gesture_handoff,transition}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.MotionSource
import com.magichandy.mobile.core.api.Percent
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

internal data class PathSection(val start: Long, val path: MotionPath, val offset: Long)

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
    override val usesWireTiming
        get() = selected != null

    override val maximumVelocity = model.travelRate(100)
    override val accelerationBound
        get() = sections.maxOf { (it.path as? ContinuousPath)?.accelerationBound ?: 7500.0 }

    private fun section(at: Long) = sections.lastOrNull { it.start <= at } ?: sections.first()

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
        val current = section((unsent - 1).coerceAtLeast(0))
        val earliest = unsent + current.offset
        val oldTurns = current.path.knotTimes(earliest, earliest + 60_000).sorted().take(3)
        for (turn in oldTurns) {
            val at = turn - current.offset
            val from = current.path.sampleAt(turn).position
            val direction = current.path.sampleAt(turn + 5).position - from
            val join = join(plan, from, direction, at) ?: continue
            val previous = sections
            val fromIndex = previous.indexOfLast { it.start <= played }.coerceAtLeast(0)
            val upto = previous.indexOf(current)
            sections =
                previous.subList(minOf(fromIndex, upto), upto + 1).filter { it.start < at } + join
            selected = score
            return true
        }
        return false
    }

    private fun join(
        plan: ContinuousPlan,
        from: Double,
        direction: Double,
        at: Long,
    ): List<PathSection>? {
        val turns = plan.knotTimes(0, plan.periodMillis).sorted()
        val candidates = turns.indices.sortedBy { abs(plan.sampleAt(turns[it]).position - from) }
        for (i in candidates) {
            val first = turns[i]
            val end = turns[(i + 1) % turns.size]
            val left = plan.sampleAt(first).position
            val right = plan.sampleAt(end).position
            if ((right - left) * direction <= 0 || (right - from) * direction <= 0) continue
            if (abs(left - from) < 0.000001) return listOf(PathSection(at, plan, first - at))
            val duration = (if (end > first) end - first else end + plan.periodMillis - first)
            val preferred =
                maxOf(100L, ceil(duration * abs(right - from) / abs(right - left)).toLong())
            val bridge = TurnBridge.fit(from, right, preferred, maximumVelocity)
            val arrival = at + bridge.duration
            return listOf(PathSection(at, bridge, -at), PathSection(arrival, plan, end - arrival))
        }
        return null
    }
}

private class TurnBridge(private val curve: QuinticSegment) : MotionPath {
    val duration = curve.duration

    override fun sampleAt(timeMillis: Long) =
        CurvePoint(
            timeMillis,
            curve
                .position((timeMillis.toDouble() / duration).coerceIn(0.0, 1.0))
                .coerceIn(0.0, 100.0),
        )

    override fun knotTimes(start: Long, end: Long): Set<Long> =
        listOf(0L, duration).filterTo(mutableSetOf()) { it in start until end }

    companion object {
        fun fit(from: Double, to: Double, preferred: Long, velocity: Double): TurnBridge {
            val left = CurveState(from, 0.0, 0.0)
            val right = CurveState(to, 0.0, 0.0)
            val unit = QuinticSegment(1000, left, right).peaks
            val scale =
                maxOf(
                    unit.velocity * 1000 / velocity,
                    kotlin.math.sqrt(unit.acceleration * 1e6 / 7500),
                    Math.cbrt(unit.jerk * 1e9 / 150000),
                )
            return TurnBridge(
                QuinticSegment(maxOf(preferred, ceil(scale * 1000 * 1.002).toLong()), left, right)
            )
        }
    }
}

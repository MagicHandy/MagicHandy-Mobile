// Ported from internal/motion/{plan,engine}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import kotlin.math.round

/**
 * Bounded-ramp manual score. A speed edit takes the first unsent reversal, where velocity is zero.
 */
internal class ManualTimeline(
    pace: Int,
    private val model: HandyModel,
    position: PlaybackPosition,
) : MotionPath {
    private data class Section(val plan: MotionPlan, val band: IntRange)

    // This immutable snapshot is also read by Pause on the session lane.
    @Volatile private var plans = listOf(section(position.band, pace, 0, position.phase))
    private var plannedPace = pace
    private var plannedBand = position.band

    fun retarget(pace: Int, firstUnsentMillis: Long, playedMillis: Long, band: IntRange = 0..100) {
        if (pace == plannedPace && band == plannedBand) return
        val previous = plans
        val index =
            previous.indexOfLast { it.plan.handoffMillis < firstUnsentMillis }.coerceAtLeast(0)
        val current = previous[index]
        val handoff = current.plan.nextTurn(firstUnsentMillis)
        // All manual knots are alternating full-stroke reversals. Avoid fractional timing error
        // when a mathematically exact reversal falls between integer stream milliseconds.
        val turn = round(current.plan.phaseAt(handoff) * legCount).toInt() % legCount
        val keepFrom =
            previous.indexOfLast { it.plan.handoffMillis <= playedMillis }.coerceIn(0, index)
        val kept =
            previous.subList(keepFrom, index + 1).takeWhile { it.plan.handoffMillis < handoff }
        plans = kept + transition(current.band, band, pace, handoff, turn)
        plannedPace = pace
        plannedBand = band
    }

    /** The first new leg starts at the previous reversal and arrives at the new opposite mark. */
    private fun transition(
        old: IntRange,
        next: IntRange,
        pace: Int,
        handoff: Long,
        turn: Int,
    ): List<Section> {
        val bridge = if (turn % 2 == 0) old.first..next.last else next.first..old.last
        val first = section(bridge, pace, handoff, turn.toDouble() / legCount)
        if (bridge == next) return listOf(first)
        val arrival = first.plan.nextTurn(handoff + 1)
        return listOf(
            first,
            section(next, pace, arrival, ((turn + 1) % legCount).toDouble() / legCount),
        )
    }

    private fun section(band: IntRange, pace: Int, handoff: Long, phase: Double): Section {
        val points =
            if (band == fullBand) knots
            else
                knots.map {
                    it.copy(
                        position =
                            if (it.position == 0.0) band.first.toDouble() else band.last.toDouble()
                    )
                }
        return Section(MotionPlan(points, pace, model, handoff, phase), band)
    }

    private fun MotionPlan.nextTurn(from: Long): Long =
        knotTimes(from, from + periodMillis + 1).min()

    private fun at(millis: Long): Section = plans.let { paths ->
        paths.lastOrNull { it.plan.handoffMillis <= millis } ?: paths.first()
    }

    fun positionAt(millis: Long): PlaybackPosition =
        at(millis).let { PlaybackPosition(it.plan.phaseAt(millis), it.band) }

    override fun sampleAt(timeMillis: Long): CurvePoint = at(timeMillis).plan.sampleAt(timeMillis)

    override fun knotTimes(start: Long, end: Long): Set<Long> = buildSet {
        val paths = plans
        for ((index, section) in paths.withIndex()) {
            val plan = section.plan
            val from = maxOf(start, plan.handoffMillis)
            val until = minOf(end, paths.getOrNull(index + 1)?.plan?.handoffMillis ?: end)
            if (from < until) addAll(plan.knotTimes(from, until))
        }
    }

    private companion object {
        const val legCount = 12
        val fullBand = 0..100
        val knots = (0..legCount).map { CurvePoint(it * 550L, if (it % 2 == 0) 0.0 else 100.0) }
    }
}

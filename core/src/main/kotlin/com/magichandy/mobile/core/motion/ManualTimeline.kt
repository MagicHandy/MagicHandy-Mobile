// Ported from internal/motion/{continuous_catalog,flow,engine}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.score.LayeredScore
import kotlin.math.roundToLong

/**
 * Desktop Full sweeps carrier. A speed edit takes the first unsent reversal, where velocity is
 * zero.
 */
internal class ManualTimeline(
    pace: Int,
    private val model: HandyModel,
    position: PlaybackPosition,
) : ContinuousPath {
    private data class Section(
        val start: Long,
        val offset: Long,
        val plan: ContinuousPlan,
        val band: IntRange,
        val isBridge: Boolean = false,
    ) {
        fun local(at: Long) = at - start + offset

        fun nextTurn(at: Long): Long {
            val from = local(at)
            return plan.knotTimes(from, from + plan.periodMillis + 1).min() + start - offset
        }
    }

    override val maximumVelocity = model.travelRate(100)
    override val accelerationBound: Double
        get() = plans.maxOf { it.plan.accelerationBound }

    override fun sparsePositionTolerance(start: Long, end: Long): Double {
        val paths = plans
        val active = at(start)
        if (
            active.isBridge ||
                paths.any { it.start in (start + 1) until end || it.start == start && start != 0L }
        )
            return precisePositionTolerance
        return active.plan.sparsePositionTolerance(active.local(start), active.local(end))
    }

    // This immutable snapshot is also read by Pause on the session lane.
    @Volatile private var plans = listOf(section(position.band, pace, 0, position.phase))
    private var plannedPace = pace
    private var plannedBand = position.band

    fun retarget(pace: Int, firstUnsentMillis: Long, playedMillis: Long, band: IntRange = 0..100) {
        if (pace == plannedPace && band == plannedBand) return
        val previous = plans
        val index = previous.indexOfLast { it.start < firstUnsentMillis }.coerceAtLeast(0)
        val current = previous[index]
        val handoff = current.nextTurn(firstUnsentMillis)
        val lowTurn =
            current.plan.sampleAt(current.local(handoff)).position <
                (current.band.first + current.band.last) / 2.0
        val keepFrom = previous.indexOfLast { it.start <= playedMillis }.coerceIn(0, index)
        val kept = previous.subList(keepFrom, index + 1).takeWhile { it.start < handoff }
        plans = kept + transition(current.band, band, pace, handoff, lowTurn)
        plannedPace = pace
        plannedBand = band
    }

    /** The first new leg starts at the previous reversal and arrives at the new opposite mark. */
    private fun transition(
        old: IntRange,
        next: IntRange,
        pace: Int,
        handoff: Long,
        lowTurn: Boolean,
    ): List<Section> {
        val bridgeBand = if (lowTurn) old.first..next.last else next.first..old.last
        // A fixed sweep is symmetric. Resume on the half-cycle that leaves this same end.
        val phase = if (lowTurn) 0.0 else 0.5 / cycles
        val first = section(bridgeBand, pace, handoff, phase)
        if (bridgeBand == next) return listOf(first)
        val arrival = first.nextTurn(handoff + 1)
        return listOf(
            first.copy(isBridge = true),
            section(next, pace, arrival, if (lowTurn) 0.5 / cycles else 0.0),
        )
    }

    private fun section(band: IntRange, pace: Int, start: Long, phase: Double): Section {
        val width = band.last - band.first
        val score =
            LayeredScore(
                low = band.first,
                high = band.last,
                speed = pace,
                floor = width,
                anchor = 0,
                memory = 8,
                paceVariation = 0,
                drift = false,
                cycles = cycles,
                layers = emptyList(),
            )
        val plan = requireNotNull(compileLayered(score, model))
        return Section(start, (phase * plan.periodMillis).roundToLong(), plan, band)
    }

    private fun at(millis: Long): Section = plans.let { paths ->
        paths.lastOrNull { it.start <= millis } ?: paths.first()
    }

    fun positionAt(millis: Long): PlaybackPosition =
        at(millis).let { PlaybackPosition(it.plan.phaseAt(it.local(millis)), it.band) }

    override fun stateAt(at: Long): CurveState = at(at).let { it.plan.stateAt(it.local(at)) }

    override fun legPeakSpeed(at: Long): Double = at(at).let { it.plan.legPeakSpeed(it.local(at)) }

    override fun sampleAt(timeMillis: Long): CurvePoint =
        at(timeMillis).let { it.plan.sampleAt(it.local(timeMillis)).copy(timeMillis = timeMillis) }

    override fun knotTimes(start: Long, end: Long): Set<Long> = buildSet {
        val paths = plans
        for ((index, section) in paths.withIndex()) {
            val from = maxOf(start, section.start)
            val until = minOf(end, paths.getOrNull(index + 1)?.start ?: end)
            if (from < until) {
                if (section.start in start until end) add(section.start)
                for (turn in section.plan.knotTimes(section.local(from), section.local(until))) add(
                    turn + section.start - section.offset
                )
            }
        }
    }

    private companion object {
        const val cycles = 8
    }
}

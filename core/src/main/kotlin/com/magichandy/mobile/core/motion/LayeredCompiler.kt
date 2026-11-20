// Ported from internal/motion/{flow_curve,flow_direction,flow_phrase,flow_timing}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.score.GestureScore
import com.magichandy.mobile.core.score.LayeredScore
import com.magichandy.mobile.core.score.PhraseGroup
import com.magichandy.mobile.core.score.RegionCycle
import com.magichandy.mobile.core.score.SemanticScore
import kotlin.math.abs
import kotlin.math.ceil

internal fun compileScore(score: SemanticScore, model: HandyModel): ContinuousPlan? =
    when (score) {
        is GestureScore -> compileGesture(score, model)
        is LayeredScore -> compileLayered(score, model)
    }

private data class TimedLeg(val stroke: StrokeLeg, val duration: Long)

private fun millis(time: Double) = maxOf(1L, ceil(time - maxOf(1e-6, time * 2e-8)).toLong())

internal fun compileLayered(spec: LayeredScore, model: HandyModel): ContinuousPlan? {
    val path = if (spec.groups.isEmpty()) fieldLegs(spec, model) else phraseLegs(spec, model)
    if (path.size < 2) return null
    val legs = path.map { it.stroke }
    val desired = LongArray(path.size) { path[it].duration }
    val budget = StrokeBudget(model.travelRate(100), spec.speed)
    fun fit(strokes: List<StrokeLeg>) = directionalFit(spec, strokes, desired, budget)
    var best = fit(legs) ?: return null
    if (spec.softness == 0 && best.sumOf { it.duration } > desired.sum()) {
        val constrained =
            if (spec.upRate == spec.downRate) best
            else fitStrokeCurves(legs, desired.copyOf(), budget) ?: return null
        val costs = legs.map { budget.scale(it.referenceCurve()) }
        for ((amount, ramp) in cruiseProfiles(budget)) {
            val candidate = legs.mapIndexed { i, leg ->
                if (constrained[i].duration <= desired[i]) leg
                else cruiseCandidate(leg, amount, ramp, costs[i], budget)
            }
            if (candidate.none { it.cruise > 0 }) continue
            val curves = fit(candidate) ?: continue
            if (curves.sumOf { it.duration } < best.sumOf { it.duration }) best = curves
        }
    }
    return ContinuousPlan.prepared(best, model.travelRate(spec.speed), budget)
}

private fun directionalFit(
    spec: LayeredScore,
    legs: List<StrokeLeg>,
    desired: LongArray,
    budget: StrokeBudget,
): List<StrokeCurve>? {
    val d = desired.copyOf()
    if (spec.upRate == spec.downRate) return fitStrokeCurves(legs, d, budget)
    if (spec.groups.isEmpty())
        repeat(16) {
            val fitted = fitStrokeCurves(legs, d, budget) ?: return null
            var changed = false
            for ((i, leg) in legs.withIndex()) if (
                (leg.to > leg.from) == (spec.upRate > spec.downRate)
            ) {
                for (j in intArrayOf((i + legs.size - 1) % legs.size, (i + 1) % legs.size)) {
                    val minimum = (d[i] * desired[j] + desired[i] - 1) / desired[i]
                    if (d[j] < minimum) {
                        d[j] = minimum
                        changed = true
                    }
                }
            }
            if (!changed) return fitted
        }
    desired.copyInto(d)
    return fitStrokeCurves(legs, d, budget, commonClock = true)
}

private fun fieldLegs(spec: LayeredScore, model: HandyModel): List<TimedLeg> {
    val field = LayeredField(spec, model)
    val turns = field.turns()
    val legs = mutableListOf<TimedLeg>()
    for (i in turns.indices) {
        val a = turns[i]
        val b = turns[(i + 1) % turns.size] + if (i == turns.lastIndex) spec.cycles else 0
        val from = field.sample(a).position
        val to = field.sample(b).position
        val nominal = 2 * field.sample((a + b) / 2).span * (b - a)
        val time = field.duration(a, b) * maxOf(1.0, abs(to - from) / nominal)
        legs +=
            TimedLeg(StrokeLeg(from, to, 1.0, 0.0, softness = spec.softness / 100.0), millis(time))
    }
    while (legs.size >= 4) {
        val i =
            legs.indices
                .filter {
                    abs(legs[it].stroke.to - legs[it].stroke.from) < 2 && legs[it].duration <= 250
                }
                .minByOrNull { abs(legs[it].stroke.to - legs[it].stroke.from) } ?: break
        val start = (i + legs.size - 1) % legs.size
        if (start + 2 >= legs.size) java.util.Collections.rotate(legs, -start)
        val k = if (start + 2 >= legs.size) 0 else start
        val old = legs[k]
        val tail = legs[k + 2]
        legs[k] =
            TimedLeg(
                old.stroke.copy(to = tail.stroke.to),
                old.duration + legs[k + 1].duration + tail.duration,
            )
        legs.subList(k + 1, k + 3).clear()
    }
    return legs.map {
        it.copy(
            duration =
                millis(
                    it.duration /
                        (if (it.stroke.to > it.stroke.from) spec.upRate / 100.0
                        else spec.downRate / 100.0)
                )
        )
    }
}

private data class Turn(val position: Int, val rate: Double)

private fun orderedTurns(groups: List<PhraseGroup>): List<Turn> {
    val turns = mutableListOf<Turn>()
    var previous = groups.last()
    for (g in groups) {
        if (g.cycle != null)
            turns += Turn(if (g.cycle == RegionCycle.UpDown) g.bottom else g.top, g.rate.toDouble())
        repeat(g.count) {
            if (g.cycle == null) {
                val slower =
                    previous.bottom <= g.bottom &&
                        (previous.top > g.top || previous.top - previous.bottom < g.top - g.bottom)
                turns += Turn(g.bottom, (if (slower) previous.rate else g.rate).toDouble())
                turns += Turn(g.top, g.rate.toDouble())
            } else {
                turns +=
                    Turn(if (g.cycle == RegionCycle.UpDown) g.top else g.bottom, g.rate.toDouble())
                turns +=
                    Turn(if (g.cycle == RegionCycle.UpDown) g.bottom else g.top, g.rate.toDouble())
            }
            previous = g
        }
    }
    if (groups.first().cycle != null)
        while (turns.size > 2) {
            val i =
                turns.indices.firstOrNull { k ->
                    (turns[k].position - turns[(k + turns.size - 1) % turns.size].position) *
                        (turns[(k + 1) % turns.size].position - turns[k].position) >= 0
                } ?: break
            val prev = turns[(i + turns.size - 1) % turns.size]
            val here = turns[i]
            val j = (i + 1) % turns.size
            val next = turns[j]
            val d1 = abs(here.position - prev.position)
            val d2 = abs(next.position - here.position)
            if (d1 + d2 > 0 && here.rate != next.rate)
                turns[j] = next.copy(rate = (d1 + d2) / (d1 / here.rate + d2 / next.rate))
            turns.removeAt(i)
        }
    return turns
}

private fun phraseLegs(s: LayeredScore, model: HandyModel): List<TimedLeg> {
    val turns = orderedTurns(s.groups)
    val count = turns.size / 2
    if (
        count < 1 ||
            turns.indices.any {
                abs(turns[it].position - turns[(it + 1) % turns.size].position) < 10
            }
    )
        return emptyList()
    val repeats = maxOf(1, (s.cycles + count - 1) / count)
    val field = LayeredField(s, model, count * repeats)
    return List(turns.size * repeats) { index ->
        val i = index % turns.size
        val from = turns[(i + turns.size - 1) % turns.size].position.toDouble()
        val to = turns[i].position.toDouble()
        val pace =
            field.pace(index / 2.0 + 0.25) * turns[i].rate / 100 *
                (if (to > from) s.upRate else s.downRate) / 100
        TimedLeg(
            StrokeLeg(from, to, 1.0, 0.0, softness = s.softness / 100.0),
            millis(1000 * abs(to - from) / (model.travelRate(s.speed) * pace)),
        )
    }
}

// Ported from internal/motion/{gesture,gesture_field,gesture_phrasing,flow_experiments}.go at
// 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.score.GestureScore
import com.magichandy.mobile.core.score.SweepDirection
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

internal fun scoreNoise(seed: UInt, index: UInt): Double {
    var x = seed xor (index * 0x9e3779b9u)
    x = (x xor (x shr 16)) * 0x7feb352du
    x = (x xor (x shr 15)) * 0x846ca68bu
    return (x xor (x shr 16)).toDouble() / UInt.MAX_VALUE.toDouble()
}

internal fun driftField(seed: UInt, cycles: Int, memory: Int, u: Double, salt: UInt): Double {
    val knots = maxOf(2, (cycles.toDouble() / memory).roundToInt())
    fun field(count: Int, key: UInt): Double {
        val phase = scoreNoise(key, 0xf00du)
        val raw = u * count / cycles + phase
        val x = raw - floor(raw / count) * count
        val i = floor(x).toInt()
        val f = x - i
        val smooth = f * f * f * f * (35 + f * (-84 + f * (70 - 20 * f)))
        val left = scoreNoise(key, i.toUInt())
        return left + (scoreNoise(key, ((i + 1) % count).toUInt()) - left) * smooth
    }
    return 0.8 * field(knots, seed xor salt) + 0.2 * field(knots * 2, seed xor salt xor 0xc2b2ae35u)
}

internal data class StrokeLeg(
    val from: Double,
    val to: Double,
    val weight: Double,
    val inertia: Double,
    val contrast: Double = 0.0,
    val softness: Double = 0.0,
    val restSeconds: Double = 0.0,
    val cruise: Double = 0.0,
    val ramp: Double = 1.0,
)

private data class Band(
    val low: Double,
    val high: Double,
    val pace: Double,
    val contrast: Double,
    val inertia: Double,
)

internal class StrokeField(private val score: GestureScore) {
    private val cycles = score.cycles
    private val memory = maxOf(2, score.memoryCycles / 2)
    private val span = (score.high - score.low).toDouble()
    private val mix = score.mix / 100.0
    private val variation = score.variation / 100.0

    private fun field(i: Int, salt: UInt, period: Int = memory) =
        driftField(score.seed, cycles, period, i.toDouble(), salt)

    private fun hash(i: Int, salt: UInt) = scoreNoise(score.seed xor salt, i.toUInt())

    fun legs(): List<StrokeLeg> {
        val anchors = anchors()
        val scales = reboundScales()
        val bands = List(cycles) { i -> band(i, anchors[i], scales[i]) }
        return buildList(cycles * 2) {
            for ((i, band) in bands.withIndex()) {
                val next = bands[(i + 1) % cycles]
                add(leg(band.low, band.high, band.pace, band.contrast, band.inertia))
                add(
                    leg(
                        band.high,
                        next.low,
                        (band.pace + next.pace) / 2,
                        (band.contrast + next.contrast) / 2,
                        (band.inertia + next.inertia) / 2,
                    )
                )
            }
        }
    }

    private fun anchors(): DoubleArray {
        val focus = score.focus / 100.0
        val roam = score.roam / 100.0
        val raw =
            DoubleArray(cycles) {
                focus * (1 - roam) + roam * field(it, 0x31d59u, maxOf(8, memory * 2))
            }
        val step = 5 / span
        return DoubleArray(cycles) { i ->
            var lo = raw[i]
            var hi = lo
            for (j in raw.indices) {
                val d = abs(i - j)
                val allowance = step * minOf(d, cycles - d)
                lo = minOf(lo, raw[j] + allowance)
                hi = maxOf(hi, raw[j] - allowance)
            }
            (lo + hi) / 2
        }
    }

    private fun reboundScales(): DoubleArray {
        val widths =
            DoubleArray(cycles) {
                maxOf(10.0, score.width * (1 - 0.25 * variation * field(it, 0x75319u)))
            }
        if (score.rebounds == 0 || mix == 0.0) return widths
        val impulse = DoubleArray(cycles) { field(it, 0x75ea1u) }
        val result = DoubleArray(cycles)
        var scale = 1.0
        var remaining = 0
        repeat(cycles * 3) { step ->
            val i = step % cycles
            if (
                remaining == 0 &&
                    impulse[i] > impulse[(i + cycles - 1) % cycles] &&
                    impulse[i] >= impulse[(i + 1) % cycles]
            )
                remaining = score.rebounds
            if (remaining > 0) {
                val next = scale * score.decay / 100
                if (widths[i] * next >= 10) {
                    scale = next
                    remaining--
                } else remaining = 0
            } else scale += (1 - scale) * 0.45
            result[i] = maxOf(10.0, widths[i] * scale)
        }
        return result
    }

    private fun band(i: Int, anchor: Double, width: Double): Band {
        val locality =
            (mix + 4 * minOf(mix, 1 - mix) * (field(i, 0x96185u) - 0.5)).coerceIn(0.0, 1.0)
        var travel = span + (width - span) * locality
        var low = score.low + (span - travel) * anchor
        val quick = flurry(i)
        if (quick > 1 && mix > 0) {
            val shorter = maxOf(10.0, travel * 0.6)
            low += (travel - shorter) * anchor
            travel = shorter
        }
        var high = low + travel
        val inset = 0.2 * variation * (high - low)
        if (inset > 0 && high - low - 2 * inset >= 10) {
            val held = score.roam == 0 && mix > 0
            if (!held || anchor > 1e-9) low += inset * hash(i, 0x1a4du)
            if (!held || anchor < 1 - 1e-9) high -= inset * hash(i, 0x2b5eu)
        }
        val accent = (1 + 1.6 * variation * (2 * field(i, 0x5c6f7u) - 1)).coerceIn(0.0, 1.8)
        val inertia =
            (score.inertia / 100.0 * (1 + variation * (2 * field(i, 0x6d708u) - 1))).coerceIn(
                0.0,
                1.0,
            )
        return Band(
            low.coerceIn(score.low.toDouble(), score.high.toDouble()),
            high.coerceIn(score.low.toDouble(), score.high.toDouble()),
            pace(i) / quick,
            minOf(0.8, score.contrast / 100.0 * accent),
            inertia,
        )
    }

    private fun pace(i: Int): Double {
        val depth = ((0.6 - field(i, 0x9b3a1u, 16)) / 0.35).coerceIn(0.0, 1.0)
        val breath = 1 + 0.6 * variation * (depth * depth * (3 - 2 * depth))
        return breath *
            (1 + 0.4 * variation * (2 * field(i, 0x46a32u) - 1)) *
            (1 + 0.15 * variation * (2 * hash(i, 0x3c6fu) - 1))
    }

    private fun flurry(i: Int): Double {
        for (back in 0..3) {
            val start = (i - back + cycles) % cycles
            if (
                hash(start, 0x4e1fu) < 0.06 * variation &&
                    back < 2 + (hash(start, 0x5f20u) * 3).toInt()
            )
                return 1.2 + 0.2 * hash(start, 0x6031u)
        }
        return 1.0
    }

    private fun leg(
        from: Double,
        to: Double,
        pace: Double,
        contrast: Double,
        inertia: Double,
    ): StrokeLeg {
        val bias =
            when (score.faster) {
                SweepDirection.Even -> 0.0
                else ->
                    if ((to > from) == (score.faster == SweepDirection.Tip)) -0.65 * contrast
                    else 0.65 * contrast
            }
        return StrokeLeg(from, to, pace * (1 + bias), inertia, contrast)
    }
}

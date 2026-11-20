// Ported from internal/motion/dynamic.go at 1b80d72e. The retired grammar exists only as an atlas
// input.
package com.magichandy.mobile.core.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

internal class OriginalCreativeComparison(
    private val center: Int,
    private val span: Int,
    private val minimum: Int,
    private val seed: UInt,
    variation: Int,
) {
    private val amount = sqrt(variation / 100.0)

    fun points(): List<CurvePoint> {
        val cycles = cycleCount()
        val p = positions(cycles)
        val legs = p.lastIndex
        val rhythm =
            Envelope(seed xor 0xd1b54a35u, knots(knots(legs, 4, 8, 160) * 3, 2, 12, 160), true)
        var time = 0L
        return p.mapIndexed { i, pos ->
            if (i > 0) {
                val delta = pos - p[i - 1]
                val phase = (i - 0.5) / legs
                val breathing =
                    0.64 * sin(2 * PI * phase + 0.55) + 0.36 * sin(6 * PI * phase + 1.75)
                val skew = (if (delta < 0) -1 else 1) * sin(4 * PI * phase + 0.90)
                val scale =
                    (1 +
                            amount *
                                (0.14 * breathing +
                                    0.10 * skew +
                                    0.22 * (2 * rhythm.at(phase) - 1)))
                        .coerceIn(0.65, 1.45)
                time +=
                    maxOf(120, (maxOf(180, (abs(delta) * 10).roundToLong()) * scale).roundToLong())
            }
            CurvePoint(time, pos)
        }
    }

    private fun cycleCount(): Int {
        val target = 30 * (400.0 / 110 * 100)
        fun enough(n: Int): Boolean {
            val p = positions(n)
            return (1 until p.size).sumOf { abs(p[it] - p[it - 1]) } >= target
        }
        for (n in 24..512 step 8) if (enough(n)) return (maxOf(24, n - 7)..n).first { enough(it) }
        return 512
    }

    private fun positions(cycles: Int): DoubleArray {
        val envelope = Envelope(seed, knots(cycles, 2, 12, 128), false)
        val texture = DoubleArray(knots(cycles, 5, 8, 96)) { noise(seed xor 0x8f4d3b21u, it) }
        val result = DoubleArray(2 * cycles + 1)
        for (i in 0 until cycles) {
            val phase = i.toDouble() / cycles
            val width = minimum + (span - minimum) * envelope.at(phase)
            val wave =
                0.58 * sin(2 * PI * phase + 0.35) +
                    0.27 * sin(6 * PI * phase + 1.10) +
                    0.15 * sin(10 * PI * phase + 2.20)
            val mid =
                (center + amount * (14 * wave + 6 * (2 * periodic(texture, phase) - 1))).coerceIn(
                    width / 2,
                    100 - width / 2,
                )
            result[2 * i] = (mid - (span / 2.0) * width / span).coerceIn(0.0, 100.0)
            result[2 * i + 1] = (mid + (span / 2.0) * width / span).coerceIn(0.0, 100.0)
        }
        result[result.lastIndex] = result[0]
        return result
    }
}

private fun knots(n: Int, per: Int, min: Int, max: Int) = ((n + per - 1) / per).coerceIn(min, max)

private fun noise(seed: UInt, index: Int): Double {
    var v = seed.toULong() + (index + 1).toULong() * 0x9e3779b97f4a7c15uL
    v = (v xor (v shr 30)) * 0xbf58476d1ce4e5b9uL
    v = (v xor (v shr 27)) * 0x94d049bb133111ebuL
    return ((v xor (v shr 31)) shr 11).toDouble() / 9007199254740992.0
}

private fun periodic(values: DoubleArray, phase: Double): Double {
    val scaled = (phase - floor(phase)) * values.size
    val i = floor(scaled).toInt() % values.size
    val f = scaled - floor(scaled)
    return values[i] +
        (values[(i + 1) % values.size] - values[i]) * (f * f * f * (f * (f * 6 - 15) + 10))
}

private class Envelope(seed: UInt, count: Int, contrast: Boolean) {
    private val values = DoubleArray(count)

    init {
        val permutations =
            listOf(
                listOf(0, 1, 2),
                listOf(0, 2, 1),
                listOf(1, 0, 2),
                listOf(1, 2, 0),
                listOf(2, 0, 1),
                listOf(2, 1, 0),
            )
        val categories = IntArray(count)
        var last = -1
        val key = seed xor if (contrast) 0xa511e9b3u else 0x85ebca6bu
        for (first in categories.indices step 3) {
            val choices = permutations.filter { it.first() != last }
            val selected =
                choices[
                    (noise(key, first / 3) * choices.size).toInt().coerceAtMost(choices.lastIndex)]
            for (k in 0 until minOf(3, count - first)) categories[first + k] = selected[k]
            last = selected.last()
        }
        if (categories.last() == categories.first())
            categories[count - 1] =
                (0..2).first { it != categories[0] && it != categories[count - 2] }
        for (i in values.indices) {
            val jitter = noise(seed xor if (contrast) 0x63d83595u else 0x1b873593u, i)
            values[i] =
                when (categories[i]) {
                    0 -> if (contrast) 0.05 + 0.15 * jitter else 0.10 + 0.20 * jitter
                    1 -> if (contrast) 0.38 + 0.24 * jitter else 0.37 + 0.26 * jitter
                    else -> 0.82 + 0.16 * jitter
                }
        }
    }

    fun at(phase: Double) = periodic(values, phase)
}

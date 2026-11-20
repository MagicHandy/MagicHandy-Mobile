// Ported from internal/motion/{flow,flow_layers,flow_turns,flow_curve}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.score.FlowLayer
import com.magichandy.mobile.core.score.LayerAxis
import com.magichandy.mobile.core.score.LayerShape
import com.magichandy.mobile.core.score.LayeredScore
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class FieldSample(val position: Double, val span: Double, val clock: Double)

internal class LayeredField(
    val score: LayeredScore,
    private val model: HandyModel,
    private val cycles: Int = score.cycles,
) {
    private val width = (score.high - score.low).toDouble()
    private val ceiling = if (score.ceiling == 0) width else score.ceiling.toDouble()
    private val rate = model.travelRate(score.speed)

    private fun wave(u: Double, memory: Double, phase: Double) =
        cos(2 * PI * (u * maxOf(1, (cycles / memory).roundToInt()) / cycles + phase))

    private fun septic(value: Double): Double {
        val u = value.coerceIn(0.0, 1.0)
        return u * u * u * u * (35 + u * (-84 + u * (70 - 20 * u)))
    }

    private fun field(u: Double, salt: UInt): Double {
        if (score.drift) return driftField(score.seed, cycles, score.memory, u, salt)
        var seed = score.seed xor salt
        fun phase(): Double {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed shr 17)
            seed = seed xor (seed shl 5)
            return seed.toDouble() / UInt.MAX_VALUE.toDouble()
        }
        return 0.5 +
            0.5 *
                (0.5 * wave(u, score.memory.toDouble(), phase()) +
                    0.3 * wave(u, score.memory * 1.7, phase()) +
                    0.2 * wave(u, score.memory / 1.6, phase()))
    }

    private fun envelope(u: Double, l: FlowLayer): Double {
        val phase = l.phase / 100.0
        val raw =
            when (l.shape) {
                LayerShape.Wave -> 0.5 + 0.5 * wave(u, l.period.toDouble(), phase)
                LayerShape.Drift ->
                    driftField(
                        score.seed,
                        cycles,
                        l.period,
                        u + phase * cycles,
                        when (l.axis) {
                            LayerAxis.Range -> 0x27d4eb2du
                            LayerAxis.Center -> 0x165667b1u
                            LayerAxis.Pace -> 0xd3a2646cu
                        },
                    )
                LayerShape.Alternate -> {
                    val waves = maxOf(1, (cycles.toDouble() / l.period).roundToInt())
                    val x = u * waves / cycles + phase
                    val index = (floor(x).toInt().mod(waves) * 4).toUInt()
                    val f = x - floor(x)
                    fun noise(i: UInt) = scoreNoise(score.seed xor 0xa511e9b3u, index + i)
                    val a = 0.08 + 0.12 * noise(0u)
                    val b = 0.36 + 0.12 * noise(1u)
                    val c = 0.56 + 0.12 * noise(2u)
                    val d = 0.84 + 0.12 * noise(3u)
                    septic((f - a) / (b - a)) * (1 - septic((f - c) / (d - c)))
                }
            }
        return if (l.inverted) 1 - raw else raw
    }

    fun pace(u: Double): Double {
        val amount = score.paceVariation / 100.0
        var pace = 1 - amount + amount * field(u, 0x85ebca6bu)
        for (l in score.layers) if (l.axis == LayerAxis.Pace) {
            val depth = (if (l.lowRate == 0) 0.5 else 1 - l.lowRate / 100.0) * (l.amount / 100.0)
            pace *= 1 - depth + depth * envelope(u, l)
        }
        return pace
    }

    fun sample(u: Double): FieldSample {
        var span = score.floor + (ceiling - score.floor) * field(u, 0x9e3779b9u)
        var anchor = score.anchor / 100.0
        for (l in score.layers) {
            val amount = l.amount / 100.0
            when (l.axis) {
                LayerAxis.Range ->
                    span =
                        (1 - amount) * span +
                            amount * (score.floor + (ceiling - score.floor) * envelope(u, l))
                LayerAxis.Center -> anchor = (1 - amount) * anchor + amount * envelope(u, l)
                LayerAxis.Pace -> Unit
            }
        }
        val phase = u - floor(u)
        val cosine = 0.5 - 0.5 * cos(2 * PI * u)
        val soft = if (phase < 0.5) septic(2 * phase) else 1 - septic(2 * phase - 1)
        val carrier = (cosine + score.softness / 100.0 * (soft - cosine)).coerceIn(0.0, 1.0)
        return FieldSample(
            score.low + anchor * (width - span) + span * carrier,
            span,
            2000 * (span + score.cadence / 100.0 * (ceiling - span)) / (rate * pace(u)),
        )
    }

    fun turns(): List<Double> {
        val count = cycles * 32
        val values = DoubleArray(count) { sample(it / 32.0).position }
        val turns = mutableListOf<Double>()
        for (i in values.indices) {
            val before = values[(i + count - 1) % count]
            val current = values[i]
            val after = values[(i + 1) % count]
            if ((current - before) * (after - current) < 0) turns += refineTurn(i, current > before)
        }
        if (turns.size > 1 && cycles - turns.last() < abs(turns.first()))
            turns.add(0, turns.removeAt(turns.lastIndex) - cycles)
        return turns
    }

    private fun refineTurn(i: Int, maximum: Boolean): Double {
        val ratio = 0.6180339887498949
        var left = (i - 1) / 32.0
        var right = (i + 1) / 32.0
        var a = right - ratio * (right - left)
        var b = left + ratio * (right - left)
        var va = sample(a).position
        var vb = sample(b).position
        repeat(32) {
            if ((va < vb) == maximum) {
                left = a
                a = b
                va = vb
                b = left + ratio * (right - left)
                vb = sample(b).position
            } else {
                right = b
                b = a
                vb = va
                a = right - ratio * (right - left)
                va = sample(a).position
            }
        }
        return (left + right) / 2
    }

    fun duration(start: Double, end: Double): Double {
        val step = (end - start) / 8
        var total = 0.0
        repeat(8) {
            val left = start + step * it
            total +=
                step *
                    (sample(left).clock +
                        4 * sample(left + step / 2).clock +
                        sample(left + step).clock) / 6
        }
        return total
    }
}

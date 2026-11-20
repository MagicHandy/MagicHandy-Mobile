// Ported from internal/estim/{protocol,render}.go at 1b80d72e.
package com.magichandy.mobile.transport.coyote

import com.magichandy.mobile.core.ports.OutputSlice
import kotlin.math.*

internal data class Strength(
    val sequence: Int = 0,
    val modeA: Int = 0,
    val a: Int = 0,
    val modeB: Int = 0,
    val b: Int = 0,
)

internal fun packet(s: Strength): ByteArray =
    ByteArray(20).apply {
        this[0] = 0xb0.toByte()
        this[1] = (s.sequence.shl(4) or (s.modeA shl 2) or s.modeB).toByte()
        this[2] = s.a.toByte()
        this[3] = s.b.toByte()
        for (i in 0..3) {
            this[4 + i] = 10
            this[12 + i] = 10
        }
    }

internal fun zero(sequence: Int) = packet(Strength(sequence, 3, 0, 3, 0))

internal fun limits(a: Int, b: Int) =
    byteArrayOf(0xbf.toByte(), a.toByte(), b.toByte(), 160.toByte(), 160.toByte(), 0, 0)

internal class PulseRenderer {
    private var envelope = 0.0
    private var tempo = 0.0
    private var hz = 0
    private var holdUntil = 0L

    fun render(slice: OutputSlice, strength: Strength): ByteArray {
        val out = packet(strength)
        val wanted = (40 + ((60 * tempo) / 5).roundToInt() * 5).coerceIn(40, 100)
        if (hz == 0 || hz != wanted && slice.atMillis >= holdUntil) {
            if (hz != 0) holdUntil = slice.atMillis + 500
            hz = wanted
        }
        val period = (1000.0 / hz).roundToInt()
        repeat(4) { i ->
            val t = slice.atMillis + i * 25L
            out[4 + i] = period.toByte()
            out[12 + i] = period.toByte()
            if (t + 25 <= slice.frames.last().timeMillis) {
                val position = slice.position(t + 12) / 100.0
                val speed = abs(slice.position(t + 25) - slice.position(t)) / 2.5
                val drive = 1 - exp(-speed / 1.5)
                envelope +=
                    (drive - envelope) * (1 - exp(-25.0 / (if (drive > envelope) 60 else 150)))
                tempo += (drive - tempo) * (1 - exp(-25.0 / 1500))
                val width = (0.15 + 0.85 * envelope) * ((t + 12) / 2000.0).coerceIn(0.0, 1.0) * 100
                out[8 + i] = (width * (0.25 + 0.75 * cos(position * PI / 2))).roundToInt().toByte()
                out[16 + i] = (width * (0.25 + 0.75 * sin(position * PI / 2))).roundToInt().toByte()
            }
        }
        return out
    }
}

// Ported from internal/motion/tempo_routines.go at 1b80d72e and Go math/rand/v2 PCG.
package com.magichandy.mobile.core.score

import java.math.BigInteger

/** Instantiated only while compiling a routine. Uses the same 128-bit PCG/DXSM stream as Go. */
private class TempoRandom(seed: ULong) {
    private val mask = BigInteger.ONE.shiftLeft(128) - BigInteger.ONE
    private val word = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE
    private var state = BigInteger(seed.toString()).shiftLeft(64) + BigInteger.valueOf(0x7e3a90)
    private val multiplier =
        BigInteger.valueOf(2549297995355413924).shiftLeft(64) +
            BigInteger.valueOf(4865540595714422341)
    private val increment =
        BigInteger.valueOf(6364136223846793005).shiftLeft(64) +
            BigInteger.valueOf(1442695040888963407)

    private fun next(): ULong {
        state = (state * multiplier + increment).and(mask)
        var hi = state.shiftRight(64).toLong().toULong()
        hi = (hi xor (hi shr 32)) * 0xda942042e4dd58b5uL
        return (hi xor (hi shr 48)) * (state.toLong().toULong() or 1uL)
    }

    fun level(base: Int, spread: Int): Int {
        val n = (2 * spread + 1).toULong()
        var product = BigInteger(next().toString()) * BigInteger(n.toString())
        val limit = (0uL - n) % n
        while (product.and(word).toLong().toULong() < limit) product =
            BigInteger(next().toString()) * BigInteger(n.toString())
        return (base + product.shiftRight(64).toInt() - spread).coerceIn(1, 100)
    }

    fun time(base: Double, variation: Double = 0.2): Double {
        val unit = (next() and ((1uL shl 53) - 1uL)).toDouble() / 9007199254740992.0
        return kotlin.math.floor(base * (1 + variation * (2 * unit - 1)) + 0.5)
    }
}

internal fun tempoRoutine(name: String, seed: ULong): List<TempoSection> {
    val r = TempoRandom(seed)
    fun steady(level: Int, seconds: Double) =
        TempoSection(TempoShape.Steady, level = level, seconds = seconds)
    fun ramp(level: Int, time: Double, seconds: Double = time) =
        TempoSection(TempoShape.Ramp, level = level, period = time, seconds = maxOf(time, seconds))
    fun wave(low: Int, high: Int, period: Double, time: Double, pulse: Boolean = false) =
        TempoSection(
            if (pulse) TempoShape.Pulse else TempoShape.Wave,
            low = low,
            high = high,
            period = period.coerceIn(2.0, 120.0),
            seconds = time,
        )
    return buildList {
        when (name) {
            "slow-build" -> {
                add(steady(r.level(18, 4), r.time(25.0)))
                for ((to, low, high) in
                    listOf(Triple(32, 28, 42), Triple(52, 46, 60), Triple(72, 64, 80))) {
                    val time = r.time(30.0)
                    add(ramp(r.level(to, 3), time))
                    add(wave(r.level(low, 3), r.level(high, 3), r.time(9.0), r.time(40.0)))
                }
                val top = r.time(25.0)
                add(ramp(r.level(86, 4), top))
            }
            "swells" -> {
                add(wave(r.level(25, 4), r.level(55, 5), r.time(14.0), r.time(60.0)))
                add(wave(r.level(35, 4), r.level(70, 5), r.time(11.0), r.time(60.0)))
                add(wave(r.level(30, 4), r.level(62, 5), r.time(16.0), r.time(45.0)))
            }
            "bursts" -> {
                val calm = r.level(32, 5)
                add(steady(calm, r.time(18.0, 0.25)))
                add(wave(calm, r.level(80, 5), r.time(3.0, 0.15), r.time(12.0), true))
                add(steady(r.level(38, 4), r.time(15.0, 0.25)))
                add(wave(r.level(36, 4), r.level(88, 5), r.time(2.5, 0.15), r.time(9.0), true))
            }
            "stairs" -> {
                for (level in listOf(20, 35, 50, 65, 80)) add(
                    steady(r.level(level, 3), r.time(18.0))
                )
                val ease = r.time(10.0)
                add(ramp(r.level(20, 3), ease))
            }
            "edge-and-release" -> {
                val climb = r.time(50.0)
                add(ramp(r.level(80, 5), climb))
                add(steady(r.level(86, 4), r.time(12.0, 0.25)))
                add(TempoSection(TempoShape.Pause, seconds = r.time(8.0, 0.25)))
                add(ramp(r.level(25, 4), 4.0, 6.0))
                add(steady(r.level(25, 4), r.time(15.0, 0.25)))
            }
            "wandering" ->
                repeat(3) {
                    val drift = r.time(10.0, 0.4)
                    val level = r.level(48, 27)
                    add(ramp(level, drift))
                    add(steady(level, r.time(12.0, 0.35)))
                }
            else -> throw IllegalArgumentException("Unknown tempo routine")
        }
    }
}

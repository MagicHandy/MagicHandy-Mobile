package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.ports.LinearPoint
import com.magichandy.mobile.core.ports.LinearSampling
import com.magichandy.mobile.core.score.LayeredScore
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class LinearSamplerTest {
    private val fullWindow = Limits(Percent(100), Percent(0), Percent(100))
    private val handy = LinearSampling(100, 100, preferredMillis = 180)

    private fun sweep(low: Int, speed: Int): ContinuousPlan =
        requireNotNull(
            compileLayered(
                LayeredScore(
                    low = low,
                    high = 100,
                    speed = speed,
                    floor = 100 - low,
                    ceiling = 100 - low,
                    anchor = 0,
                    memory = 8,
                    paceVariation = 0,
                    drift = false,
                    cycles = 6,
                    layers = emptyList(),
                ),
                HandyModel.Original,
            )
        )

    private fun twelveSeconds(path: MotionPath, policy: LinearSampling): List<LinearPoint> {
        val sampler = LinearSampler(path, policy)
        return buildList {
            for (end in 1000L..12000L step 1000L) addAll(sampler.through(end, fullWindow))
        }
            .takeWhile { it.timeMillis < 12000 }
    }

    @Test
    fun `desktop Handy timing preserves every reversal across append seams and the preferred spacing stays optional`() {
        for ((low, speed, expectedPoints) in
            listOf(Triple(0, 25, 53), Triple(80, 25, 66), Triple(80, 100, 100))) {
            val path = sweep(low, speed)
            val points = twelveSeconds(path, handy)
            assertEquals(expectedPoints, points.size)
            val times = points.map { it.timeMillis }.toSet()
            assertTrue(path.knotTimes(0, 12000).all { it in times })
            assertTrue(
                points.zipWithNext().all { (l, r) ->
                    r.timeMillis - l.timeMillis >= handy.intervalMillis &&
                        r.position.roundToInt() != l.position.roundToInt()
                }
            )
            if (speed == 100)
                assertTrue(
                    points.zipWithNext().any { (l, r) -> r.timeMillis - l.timeMillis < 180L }
                )
            if (low == 0)
                assertTrue(points.size < twelveSeconds(path, handy.copy(preferredMillis = 0)).size)
        }
    }

    @Test
    fun `an infeasible true turn rejects the batch and the read cursor stays at its prior position`() {
        val sampler = LinearSampler(sweep(90, 100), handy.copy(gapMillis = 300))
        assertFailsWith<IllegalArgumentException> { sampler.through(2000, fullWindow) }
        assertEquals(0L, sampler.nextMillis)
    }

    @Test
    fun `authored holds keep both boundaries and finite tests never extend beyond their end`() {
        val shape =
            object : MotionPath {
                override fun sampleAt(timeMillis: Long): CurvePoint {
                    val at = timeMillis % 1500
                    val p =
                        when {
                            at < 400 -> at / 4.0
                            at < 800 -> 100.0
                            else -> (1500L - at) / 7.0
                        }
                    return CurvePoint(timeMillis, p)
                }

                override fun knotTimes(start: Long, end: Long) = buildSet {
                    for (cycle in start / 1500..end / 1500) for (offset in listOf(0L, 400L, 800L)) {
                        val at = cycle * 1500 + offset
                        if (at in start until end) add(at)
                    }
                }
            }
        val fit = LinearSampler(shape, handy)
        val frames = fit.through(1500, fullWindow, finite = true)
        assertEquals(listOf(0L, 400L, 800L, 1500L), frames.map { it.timeMillis })
        assertEquals(listOf(0.0, 100.0, 100.0, 0.0), frames.map { it.position })
    }

    @Test
    fun `timing gap and physical steps constrain the shared path across append seams`() {
        val path =
            object : MotionPath {
                override fun sampleAt(timeMillis: Long) =
                    CurvePoint(timeMillis, 50.13 + 31 * sin(timeMillis / 600.0))

                override fun knotTimes(start: Long, end: Long) = emptySet<Long>()
            }
        val wide = Limits(Percent(60), Percent(0), Percent(100))
        val coarse = LinearSampling(100, 12, 400)
        val fit = LinearSampler(path, coarse)
        val frames = fit.through(1200, wide) + fit.through(2300, wide)
        assertEquals(0, frames.first().timeMillis)
        assertTrue(frames.zipWithNext().all { (a, b) -> b.timeMillis - a.timeMillis >= 110 })
        assertTrue(frames.all { it.position == path.sampleAt(it.timeMillis).position })
        val narrow = wide.copy(strokeHigh = Percent(20))
        // Only four physical steps fit inside a 20% window on this 20-step feature.
        val fine = LinearSampling(0, 20)
        assertTrue(
            LinearSampler(path, fine).through(2000, narrow).size <
                LinearSampler(path, fine).through(2000, wide).size
        )
        assertTrue(frames.last().timeMillis >= 2300)
    }
}

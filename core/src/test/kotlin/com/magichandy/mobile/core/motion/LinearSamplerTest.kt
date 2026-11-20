package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import kotlin.math.*
import kotlin.test.*

internal class LinearSamplerTest {
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
        val fine = LinearSampling(0, 100, 300)
        assertTrue(
            LinearSampler(path, fine).through(2000, narrow).size <
                LinearSampler(path, fine).through(2000, wide).size
        )
        assertTrue(frames.last().timeMillis >= 2300)
    }
}

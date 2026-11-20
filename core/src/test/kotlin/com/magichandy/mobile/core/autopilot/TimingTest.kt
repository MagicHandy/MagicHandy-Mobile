package com.magichandy.mobile.core.autopilot

import com.magichandy.mobile.core.api.ChangeFrequency
import kotlin.random.Random
import kotlin.test.*

internal class TimingTest {
    @Test
    fun `planner horizons respect the user floor and five minute ceiling even after jitter`() {
        val timing = Timing(Random(23))
        assertEquals(45, timing.floor(ChangeFrequency(1)))
        assertEquals(10, timing.floor(ChangeFrequency(4)))
        assertEquals(8, timing.floor(ChangeFrequency(8)))
        repeat(100) {
            assertEquals(45L, timing.motion(ChangeFrequency(1), "soon", 1).inWholeSeconds)
            assertEquals(300L, timing.motion(ChangeFrequency(8), "later", 500).inWholeSeconds)
        }
    }

    @Test
    fun `timing replies select distinct triangular distributions inside the saved window`() {
        val t = Timing(Random(7))
        val draws =
            listOf("soon", "normal", "later").map { category ->
                List(400) { t.motion(ChangeFrequency(4), category, null).inWholeSeconds }
            }
        assertTrue(draws.flatten().all { it in 20..60 })
        assertTrue(draws.all { it.distinct().size > 5 })
        assertTrue(draws[0].average() in 25.0..29.0)
        assertTrue(draws[1].average() in 38.0..42.0)
        assertTrue(draws[2].average() in 51.0..55.0)
    }
}

package com.magichandy.mobile.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A spoken Stop is checked while someone speaks, so it never waits for the turn to end. */
internal class SpokenStopWatchTest {
    @Test
    fun `speech is checked every 0_4 s from its start`() {
        val watch = SpokenStopWatch(turnThreshold = 0.5f, window = WINDOW)
        val checks = feed(watch, List(10) { 0.1f } + List(40) { 0.9f })
        // 0.4 s is 12.5 windows of 32 ms, so the checks fall on every thirteenth window.
        assertEquals(listOf(22, 35, 48), checks)
    }

    @Test
    fun `silence is never checked`() {
        val watch = SpokenStopWatch(turnThreshold = 0.5f, window = WINDOW)
        assertEquals(emptyList<Int>(), feed(watch, List(100) { 0.1f }))
    }

    @Test
    fun `the checks carry on through the pause before a turn ends`() {
        val watch = SpokenStopWatch(turnThreshold = 0.5f, window = WINDOW)
        val checks = feed(watch, List(13) { 0.9f } + List(40) { 0.05f })
        // Speech, then 0.6 s of quiet: checks at 0.4 s and 0.8 s, none once the pause has passed.
        assertEquals(listOf(12, 25), checks)
    }

    @Test
    fun `a stricter turn threshold can't hide a quiet Stop`() {
        val watch = SpokenStopWatch(turnThreshold = 0.6f, window = WINDOW)
        val checks = feed(watch, List(20) { 0.55f })
        assertTrue(checks.isNotEmpty())
    }

    @Test
    fun `a looser turn threshold starts the checks sooner`() {
        val watch = SpokenStopWatch(turnThreshold = 0.3f, window = WINDOW)
        val checks = feed(watch, List(20) { 0.35f })
        assertTrue(checks.isNotEmpty())
    }

    @Test
    fun `a turn still in progress keeps the checks going`() {
        val watch = SpokenStopWatch(turnThreshold = 0.5f, window = WINDOW)
        val checks =
            (0 until 40).mapNotNull { index ->
                index.takeIf { watch.accept(0.1f, isTurnSpeaking = true) }
            }
        assertEquals(listOf(12, 25, 38), checks)
    }

    @Test
    fun `each check hears the last one and a half seconds`() {
        assertEquals(24_000L, SpokenStopWatch.tailSamples)
    }

    /** The windows at which the watch asks for a check. */
    private fun feed(watch: SpokenStopWatch, probabilities: List<Float>): List<Int> =
        probabilities.mapIndexedNotNull { index, probability ->
            index.takeIf { watch.accept(probability, isTurnSpeaking = false) }
        }

    private companion object {
        /** Silero's window: 32 ms at 16 kHz. */
        const val WINDOW = 512
    }
}

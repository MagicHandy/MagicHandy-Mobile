package com.magichandy.mobile.voice

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Turns start and end as Silero's own iterator would, and keep their audio's place. */
internal class EndpointerTest {
    @Test
    fun `speech starts at the threshold and ends after the silence`() {
        val endpointer = Endpointer(threshold = 0.5f, silenceSeconds = 0.6f, window = WINDOW)
        val spans = feed(endpointer, List(10) { 0.1f } + List(20) { 0.9f } + List(30) { 0.05f })
        assertEquals(listOf(Span(10L * WINDOW, 30L * WINDOW)), spans)
        assertFalse(endpointer.isSpeaking)
    }

    @Test
    fun `the turn ends only once the silence has lasted`() {
        val endpointer = Endpointer(threshold = 0.5f, silenceSeconds = 0.6f, window = WINDOW)
        feed(endpointer, List(20) { 0.9f } + List(18) { 0.05f })
        assertTrue(endpointer.isSpeaking)
        assertEquals(Span(0, 20L * WINDOW), endpointer.accept(0.05f))
    }

    @Test
    fun `a dip between the two thresholds doesn't end the turn`() {
        val endpointer = Endpointer(threshold = 0.5f, silenceSeconds = 0.6f, window = WINDOW)
        val spans = feed(endpointer, List(10) { 0.9f } + List(40) { 0.4f } + List(30) { 0.1f })
        assertEquals(listOf(Span(0, 50L * WINDOW)), spans)
    }

    @Test
    fun `speech after a short pause continues the same turn`() {
        val endpointer = Endpointer(threshold = 0.5f, silenceSeconds = 0.6f, window = WINDOW)
        val spans =
            feed(
                endpointer,
                List(10) { 0.9f } + List(10) { 0.1f } + List(10) { 0.9f } + List(30) { 0.1f },
            )
        assertEquals(listOf(Span(0, 30L * WINDOW)), spans)
    }

    @Test
    fun `a click shorter than a tenth of a second is dropped`() {
        val endpointer = Endpointer(threshold = 0.5f, silenceSeconds = 0.6f, window = WINDOW)
        assertEquals(emptyList<Span>(), feed(endpointer, List(3) { 0.9f } + List(30) { 0.1f }))
        assertFalse(endpointer.isSpeaking)
    }

    @Test
    fun `a sound shorter than a word comes back short, for the Stop checks only`() {
        val endpointer = Endpointer(threshold = 0.5f, silenceSeconds = 0.6f, window = WINDOW)
        val short = feed(endpointer, List(4) { 0.9f } + List(30) { 0.1f })
        assertEquals(listOf(Span(0, 4L * WINDOW, isShort = true)), short)
        val longest = feed(endpointer, List(7) { 0.9f } + List(30) { 0.1f })
        assertEquals(listOf(Span(34L * WINDOW, 41L * WINDOW, isShort = true)), longest)
        val word = feed(endpointer, List(8) { 0.9f } + List(30) { 0.1f })
        assertEquals(listOf(Span(71L * WINDOW, 79L * WINDOW)), word)
    }

    @Test
    fun `a long turn is cut at the limit and carries on`() {
        val endpointer =
            Endpointer(0.5f, silenceSeconds = 0.6f, window = WINDOW, maxSpeechSeconds = 1f)
        val spans = feed(endpointer, List(80) { 0.9f })
        assertEquals(listOf(Span(0, 32L * WINDOW), Span(32L * WINDOW, 64L * WINDOW)), spans)
        assertTrue(endpointer.isSpeaking)
    }

    @Test
    fun `a long turn is cut at its last pause`() {
        val endpointer =
            Endpointer(0.5f, silenceSeconds = 0.6f, window = WINDOW, maxSpeechSeconds = 1f)
        val spans = feed(endpointer, List(25) { 0.9f } + List(10) { 0.1f })
        assertEquals(listOf(Span(0, 25L * WINDOW)), spans)
    }

    @Test
    fun `flushing ends the turn in progress`() {
        val endpointer = Endpointer(threshold = 0.5f, silenceSeconds = 0.6f, window = WINDOW)
        feed(endpointer, List(5) { 0.1f } + List(20) { 0.9f })
        assertEquals(Span(5L * WINDOW, 25L * WINDOW), endpointer.flush())
        assertEquals(null, endpointer.flush())
    }

    @Test
    fun `history slices by position`() {
        val history = AudioHistory(capacity = 1_000)
        history.add(FloatArray(600) { it.toFloat() })
        history.add(FloatArray(400) { (600 + it).toFloat() })
        assertArrayEquals(FloatArray(100) { (100 + it).toFloat() }, history.slice(100, 200))
        assertArrayEquals(FloatArray(50) { (950 + it).toFloat() }, history.slice(950, 1_200))
    }

    @Test
    fun `history keeps only the latest audio`() {
        val history = AudioHistory(capacity = 500)
        history.add(FloatArray(1_200) { it.toFloat() })
        assertArrayEquals(FloatArray(100) { (700 + it).toFloat() }, history.slice(-50, 800))
        assertEquals(0, history.slice(0, 600).size)
    }

    private fun feed(endpointer: Endpointer, probabilities: List<Float>): List<Span> =
        probabilities.mapNotNull {
            endpointer.accept(it)
        }

    private companion object {
        /** Silero's window: 32 ms at 16 kHz. */
        const val WINDOW = 512
    }
}

package com.magichandy.mobile.voicelab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Transcripts are scored against the line read out, as the clip results show them. */
internal class WordScoreTest {
    @Test
    fun `case and punctuation don't count`() {
        val score = scoreTranscript("Go a little slower, please.", "go a little slower please")
        assertEquals(0, score.errors)
        assertEquals(5, score.words)
        assertEquals(List(5) { Mark.Right }, score.marks.map { it.mark })
    }

    @Test
    fun `a wrong word, a missing one and an extra one each count once`() {
        val score =
            scoreTranscript(
                "Keep that rhythm and go a little deeper.",
                "Keep that written and go a deeper now",
            )
        assertEquals(3, score.errors)
        assertEquals(8, score.words)
        assertEquals(
            listOf(
                "Keep" to Mark.Right,
                "that" to Mark.Right,
                "written" to Mark.Wrong,
                "and" to Mark.Right,
                "go" to Mark.Right,
                "a" to Mark.Right,
                "little" to Mark.Missing,
                "deeper" to Mark.Right,
                "now" to Mark.Extra,
            ),
            score.marks.map { it.text to it.mark },
        )
    }

    @Test
    fun `curly and straight apostrophes are the same word`() {
        assertEquals(0, scoreTranscript("Don't stop.", "Don’t stop").errors)
    }

    @Test
    fun `an empty transcript misses every word`() {
        val score = scoreTranscript("Stop right now.", "")
        assertEquals(3, score.errors)
        assertEquals(List(3) { Mark.Missing }, score.marks.map { it.mark })
    }

    @Test
    fun `Japanese and Chinese are scored per character`() {
        val japanese = scoreTranscript("ゆっくりして", "ゆっくりしで")
        assertEquals(1, japanese.errors)
        assertEquals(6, japanese.words)
        assertEquals(0, scoreTranscript("慢一点。", "慢一点").errors)
    }
}

package com.magichandy.mobile.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Quiet speech is raised to a working level, without lifting the noise too far or clipping. */
internal class AutoLevelTest {
    @Test
    fun `a tone's level is its RMS in dBFS`() {
        assertEquals(-20f, levelDb(tone(-20f, WINDOW)), 0.05f)
        assertEquals(SILENCE_DB, levelDb(FloatArray(WINDOW)))
    }

    @Test
    fun `quiet speech in a quiet room is raised to the target`() {
        val level = AutoLevel()
        feed(level, seconds = 1f, db = -80f, isSpeech = false)
        feed(level, seconds = 4f, db = -52f, isSpeech = true)
        assertEquals(AutoLevel.TARGET_DB + 52f, level.gainDb, 0.5f)
        assertEquals(-52f, level.speechDb!!, 0.5f)
    }

    @Test
    fun `the noise floor holds the gain down`() {
        val level = AutoLevel()
        feed(level, seconds = 1f, db = -60f, isSpeech = false)
        feed(level, seconds = 4f, db = -52f, isSpeech = true)
        assertEquals(AutoLevel.NOISE_CEILING_DB + 60f, level.gainDb, 0.5f)
    }

    @Test
    fun `before any speech the gain comes from the noise floor, at once`() {
        val level = AutoLevel()
        feed(level, seconds = 0.6f, db = -70f, isSpeech = false)
        assertEquals(AutoLevel.NOISE_CEILING_DB + 70f, level.gainDb, 0.5f)
    }

    @Test
    fun `the recorder's first moments don't set the gain`() {
        val level = AutoLevel()
        // The recorder ramps up from near silence before the room's real noise arrives.
        feed(level, seconds = 0.15f, db = -85f, isSpeech = false)
        feed(level, seconds = 0.5f, db = -60f, isSpeech = false)
        assertEquals(AutoLevel.NOISE_CEILING_DB + 60f, level.gainDb, 0.5f)
    }

    @Test
    fun `after speaking close, a distant voice is raised again`() {
        val level = AutoLevel()
        feed(level, seconds = 1f, db = -80f, isSpeech = false)
        feed(level, seconds = 2f, db = -25f, isSpeech = true)
        assertEquals(0f, level.gainDb, 0.5f)
        // A short pause keeps the speech level.
        feed(level, seconds = 5f, db = -80f, isSpeech = false)
        assertEquals(-25f, level.speechDb!!, 0.5f)
        // A longer quiet while you move away forgets it, and the gain comes back up.
        feed(level, seconds = AutoLevel.SPEECH_MEMORY_SECONDS + 1f, db = -80f, isSpeech = false)
        assertNull(level.speechDb)
        assertTrue(level.gainDb >= 25f)
        // The distant voice sets the speech level again, and with it the gain.
        feed(level, seconds = 3f, db = -45f, isSpeech = true)
        assertEquals(AutoLevel.TARGET_DB + 45f, level.gainDb, 1f)
    }

    @Test
    fun `talking at once doesn't set the noise floor`() {
        val level = AutoLevel()
        feed(level, seconds = 0.2f, db = -80f, isSpeech = false)
        feed(level, seconds = 1f, db = -30f, isSpeech = true)
        assertNull(level.noiseDb)
        feed(level, seconds = 0.5f, db = -80f, isSpeech = false)
        assertTrue(level.noiseDb!! < -75f)
    }

    @Test
    fun `a tap while settling doesn't hold the floor up`() {
        val level = AutoLevel()
        feed(level, seconds = 0.2f, db = -80f, isSpeech = false)
        feed(level, seconds = 0.064f, db = -30f, isSpeech = false)
        feed(level, seconds = 0.3f, db = -80f, isSpeech = false)
        assertEquals(-80f, level.noiseDb!!, 0.5f)
        assertEquals(AutoLevel.MAX_GAIN_DB, level.gainDb, 0.5f)
    }

    @Test
    fun `digital silence still counts as time without speech`() {
        val level = AutoLevel()
        feed(level, seconds = 1f, db = -80f, isSpeech = false)
        feed(level, seconds = 2f, db = -25f, isSpeech = true)
        repeat(((AutoLevel.SPEECH_MEMORY_SECONDS + 1f) / WINDOW_SECONDS).toInt()) {
            val zeros = FloatArray(WINDOW)
            level.apply(zeros)
            level.update(levelDb(zeros), isSpeech = false, seconds = WINDOW_SECONDS)
        }
        assertNull(level.speechDb)
    }

    @Test
    fun `the noise floor never rises above the room's level`() {
        val level = AutoLevel()
        feed(level, seconds = 2f, db = -80f, isSpeech = false)
        feed(level, seconds = 1f, db = -79f, isSpeech = false)
        assertEquals(-79f, level.noiseDb!!, 0.1f)
    }

    @Test
    fun `a few quiet windows don't drag the noise floor down`() {
        val level = AutoLevel()
        feed(level, seconds = 2f, db = -60f, isSpeech = false)
        // A stroker's quiet turnaround: three windows, about 0.1 s.
        feed(level, seconds = 0.1f, db = -80f, isSpeech = false)
        assertTrue(level.noiseDb!! > -62f)
    }

    @Test
    fun `the noise floor follows a quieter room within seconds`() {
        val level = AutoLevel()
        feed(level, seconds = 2f, db = -60f, isSpeech = false)
        feed(level, seconds = 3f, db = -80f, isSpeech = false)
        assertEquals(-80f, level.noiseDb!!, 1f)
    }

    @Test
    fun `after settling the gain rises gradually`() {
        val level = AutoLevel()
        feed(level, seconds = 1f, db = -60f, isSpeech = false)
        val before = level.gainDb
        // The room goes quiet: the gain may rise, but only a few dB in half a second.
        feed(level, seconds = 0.5f, db = -85f, isSpeech = false)
        assertTrue(level.gainDb > before)
        assertTrue(level.gainDb - before <= 3.5f)
    }

    @Test
    fun `a loud window is limited rather than clipped`() {
        val level = AutoLevel()
        feed(level, seconds = 1f, db = -85f, isSpeech = false)
        val loud = tone(-9f, WINDOW)
        level.apply(loud)
        assertTrue(loud.maxOf { abs(it) } <= 0.9f + 1e-4f)
        assertTrue(level.gainDb < 10f)
    }

    @Test
    fun `a fixed gain survives a loud click`() {
        val level = AutoLevel(20f, adapts = false)
        val click = tone(-9f, WINDOW)
        level.apply(click)
        assertTrue(click.maxOf { abs(it) } <= 0.9f + 1e-4f)
        assertEquals(20f, level.gainDb)
        // The next window ramps back to the fixed gain; the one after has it in full.
        level.apply(tone(-60f, WINDOW))
        val after = tone(-60f, WINDOW)
        level.apply(after)
        assertEquals(-40f, levelDb(after), 0.2f)
    }

    @Test
    fun `digital silence isn't taken for the noise floor`() {
        val level = AutoLevel()
        repeat(20) {
            val window = FloatArray(WINDOW)
            level.apply(window)
            level.update(levelDb(window), isSpeech = false, seconds = WINDOW_SECONDS)
        }
        assertNull(level.noiseDb)
        assertEquals(0f, level.gainDb)
    }

    @Test
    fun `levelled audio becomes 16-bit audio held at full scale`() {
        val pcm = toPcm(floatArrayOf(0f, 0.5f, -1f, 1.5f, -2f))
        assertArrayEquals(shortArrayOf(0, 16_384, -32_768, 32_767, -32_768), pcm)
    }

    /** Feeds [seconds] of a tone at [db] through [level], as the segmenter does. */
    private fun feed(level: AutoLevel, seconds: Float, db: Float, isSpeech: Boolean) {
        repeat((seconds / WINDOW_SECONDS).toInt()) {
            val window = tone(db, WINDOW)
            val inputDb = levelDb(window)
            level.apply(window)
            level.update(inputDb, isSpeech, WINDOW_SECONDS)
        }
    }

    private companion object {
        const val WINDOW = 512
        const val WINDOW_SECONDS = WINDOW / 16_000f

        /** A 440 Hz tone whose RMS is [db] dBFS. */
        fun tone(db: Float, length: Int): FloatArray {
            val amplitude = sqrt(2.0) * 10.0.pow(db / 20.0)
            return FloatArray(length) { (amplitude * sin(2 * PI * 440 * it / 16_000)).toFloat() }
        }
    }
}

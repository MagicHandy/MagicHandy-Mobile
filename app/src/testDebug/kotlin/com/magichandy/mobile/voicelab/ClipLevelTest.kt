package com.magichandy.mobile.voicelab

import com.magichandy.mobile.voice.AutoLevel
import com.magichandy.mobile.voice.SpeechLevel
import com.magichandy.mobile.voice.levelDb
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The voice test levels whole clips with the gain voice input would settle on. */
internal class ClipLevelTest {
    @Test
    fun `a quiet clip is raised and its level reported`() {
        val clip = tone(-80f, 8_000) + tone(-50f, 24_000) + tone(-80f, 8_000)
        val levelled = levelClip(clip, raise = true)
        assertEquals(-50f, levelled.level.inputDb, 0.5f)
        assertEquals(AutoLevel.TARGET_DB + 50f, levelled.level.gainDb, 0.5f)
        val speech = levelled.samples.copyOfRange(12_000, 28_000)
        assertEquals(AutoLevel.TARGET_DB, levelDb(speech), 0.5f)
    }

    @Test
    fun `a clip that isn't raised is only measured`() {
        val clip = tone(-80f, 8_000) + tone(-40f, 16_000)
        val levelled = levelClip(clip, raise = false)
        assertArrayEquals(clip, levelled.samples)
        assertEquals(-40f, levelled.level.inputDb, 0.5f)
        assertEquals(0f, levelled.level.gainDb)
    }

    @Test
    fun `a turn's level reads as decibels`() {
        assertEquals("at −51 dBFS, raised 24 dB", SpeechLevel(-51.2f, 23.8f).describe())
        assertEquals("at −31 dBFS", SpeechLevel(-31f, 0f).describe())
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

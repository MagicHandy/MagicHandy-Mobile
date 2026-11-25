package com.magichandy.mobile.voicelab

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The voice test's recordings round-trip through WAV files at 16 kHz. */
internal class WavTest {
    @TempDir lateinit var root: File

    @Test
    fun `a WAV file reads back as it was written`() {
        val pcm = ShortArray(16_000) { (it % 2_000 - 1_000).toShort() }
        val file = File(root, "clip.wav")
        writeWav(file, pcm)
        assertArrayEquals(pcm, readWav(file))
        assertEquals(44L + pcm.size * 2, Files.size(file.toPath()))
    }

    @Test
    fun `audio at another rate is resampled to 16 kHz`() {
        // A straight line stays a straight line, two thirds as long.
        val ramp = ShortArray(24_000) { it.toShort() }
        val resampled = resample(ramp, 24_000, 16_000)
        assertEquals(16_000, resampled.size)
        assertEquals(0, resampled.first().toInt())
        assertEquals(15, resampled[10].toInt())
        assertArrayEquals(ramp, resample(ramp, 16_000, 16_000))
    }
}

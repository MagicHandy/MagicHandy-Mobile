package com.magichandy.mobile.voicelab

import com.magichandy.mobile.core.api.SpeechLevel
import com.magichandy.mobile.voice.AutoLevel
import com.magichandy.mobile.voice.SILENCE_DB
import com.magichandy.mobile.voice.levelDb
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/** Such as "at −51 dBFS, raised 24 dB". */
fun SpeechLevel.describe(): String =
    "at " +
        decibels(inputDb) +
        " dBFS" +
        if (gainDb >= SHOWN_GAIN_DB) ", raised " + decibels(gainDb) + " dB" else ""

private const val SHOWN_GAIN_DB = 0.5f

/** [value] to the nearest decibel, with a true minus sign. */
fun decibels(value: Float): String = "%.0f".format(Locale.ROOT, value).replace('-', '−')

/** A recording after [levelClip], with the level it arrived at and the gain it was given. */
class Levelled(val samples: FloatArray, val level: SpeechLevel)

/**
 * Levels a whole recording at once, with the gain [AutoLevel] would settle on: the noise floor is
 * its quietest tenth of windows and its speech the windows clearly above that. With [raise] false
 * it measures the level and leaves the audio as it is.
 */
fun levelClip(samples: FloatArray, raise: Boolean): Levelled {
    val levels =
        (0 until samples.size / CLIP_WINDOW)
            .map { levelDb(samples.copyOfRange(it * CLIP_WINDOW, (it + 1) * CLIP_WINDOW)) }
            .filter { it > SILENCE_DB }
            .sorted()
    if (levels.isEmpty()) return Levelled(samples, SpeechLevel(SILENCE_DB, 0f))
    val noise = levels[levels.size / NOISE_PERCENTILE_DIVISOR]
    val speech = levels.filter { it > noise + CLIP_SPEECH_OVER_NOISE_DB }
    val speechDb = if (speech.isEmpty()) levels.last() else averageDb(speech)
    if (!raise) return Levelled(samples, SpeechLevel(speechDb, 0f))
    val gain = AutoLevel.wantedGain(noise, speechDb) ?: 0f
    val level = AutoLevel(gain, adapts = false)
    val out = samples.copyOf()
    var offset = 0
    while (offset < out.size) {
        val end = minOf(offset + CLIP_WINDOW, out.size)
        val window = out.copyOfRange(offset, end)
        level.apply(window)
        window.copyInto(out, offset)
        offset = end
    }
    return Levelled(out, SpeechLevel(speechDb, gain))
}

/** The level of windows at [levels] dBFS taken together: their energies averaged. */
fun averageDb(levels: List<Float>): Float {
    if (levels.isEmpty()) return SILENCE_DB
    val energy = levels.sumOf { BASE.pow(it / DB_PER_BEL.toDouble()) } / levels.size
    return (DB_PER_BEL * log10(energy)).toFloat()
}

private const val BASE = 10.0
private const val DB_PER_BEL = 10

/** 32 ms, the window Silero judges. */
private const val CLIP_WINDOW = 512
private const val NOISE_PERCENTILE_DIVISOR = 10
private const val CLIP_SPEECH_OVER_NOISE_DB = 10f

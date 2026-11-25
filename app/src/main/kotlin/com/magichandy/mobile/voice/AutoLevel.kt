package com.magichandy.mobile.voice

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * How loud speech reached the phone and how much it was raised.
 *
 * @property inputDb the speech's level from the microphone, in dBFS.
 * @property gainDb the gain applied before the detector and the recognizer heard it.
 */
data class SpeechLevel(val inputDb: Float, val gainDb: Float)

/**
 * Raises quiet speech to a working level before the speech detector and the engines hear it.
 *
 * Android's speech-recognition path must have no automatic gain (CDD 5.4), and its sensitivity puts
 * ordinary speech a metre away near −52 dBFS, within a few dB of where Silero starts losing words
 * in a reverberant room. This works like WebRTC's adaptive digital gain (AGC2):
 * - it follows the noise floor and the level of the windows the detector calls speech;
 * - it moves the gain toward what brings speech to [TARGET_DB], without lifting the noise above
 *   [NOISE_CEILING_DB] or the gain above [MAX_GAIN_DB];
 * - the gain changes slowly, so it doesn't pump within a word, and drops at once when a window
 *   would clip;
 * - after [SPEECH_MEMORY_SECONDS] without speech it forgets how loud speech was, so moving away
 *   from the phone after speaking close to it doesn't leave the gain at 0 dB.
 *
 * Feed it one window at a time: [apply], then [update] once the detector has judged the window.
 * With [adapts] false it holds [gainDb], as for a whole clip, and only limits peaks.
 */
class AutoLevel(gainDb: Float = 0f, private val adapts: Boolean = true) {
    /** The gain now applied, in dB. */
    var gainDb: Float = gainDb
        private set

    /** The noise floor heard so far, in dBFS, or null before any sound. */
    var noiseDb: Float? = null
        private set

    /** The level of recent speech, in dBFS, or null before any speech or once it's forgotten. */
    var speechDb: Float? = null
        private set

    private var applied = decibelsToGain(gainDb)
    private var heardSeconds = 0f
    private var quietSeconds = 0f

    /**
     * Applies the gain to [window] in place and returns the gain it applied, in dB. The gain ramps
     * across the window from the last window's, unless this window's peak would clip: then it gets
     * less gain at once. Adapting, that lower gain holds and recovers slowly; a fixed gain ramps
     * back across the next window and applies in full from the one after.
     */
    fun apply(window: FloatArray): Float {
        var peak = 0f
        for (sample in window) peak = maxOf(peak, abs(sample))
        val limited = peak > 0f && peak * decibelsToGain(gainDb) > LIMIT
        val windowDb = if (limited) gainToDecibels(LIMIT / peak) else gainDb
        if (limited && adapts) gainDb = windowDb
        val target = decibelsToGain(windowDb)
        val start = if (limited) target else applied
        val step = (target - start) / window.size
        for (index in window.indices) {
            window[index] = (window[index] * (start + step * (index + 1))).coerceIn(-1f, 1f)
        }
        applied = target
        return windowDb
    }

    /**
     * Learns from a window whose level from the microphone was [inputDb] and which lasted
     * [seconds], and moves the gain. [isSpeech] is the detector's verdict on it.
     */
    fun update(inputDb: Float, isSpeech: Boolean, seconds: Float) {
        if (!adapts) return
        if (inputDb <= DIGITAL_SILENCE_DB) {
            // Exact zeros come from the recorder starting up, or from a path that gates silence:
            // not the room's noise, but time without speech all the same.
            if (heardSeconds > STARTUP_SECONDS) passQuiet(seconds)
            return
        }
        heardSeconds += seconds
        // The first moments of sound say little about the room either: the recorder ramps up.
        if (heardSeconds <= STARTUP_SECONDS) return
        val settling = heardSeconds <= STARTUP_SECONDS + SETTLE_SECONDS
        val noise = noiseDb
        noiseDb =
            when {
                // The floor comes only from windows without speech, so talking at once can't
                // set it.
                noise == null -> inputDb.takeUnless { isSpeech }
                // While settling it drops at once, past a tap or a first word. After that it falls
                // at a limited rate, so a stroker's quiet turnarounds don't drag it down and pump
                // the gain.
                inputDb < noise ->
                    if (settling) inputDb
                    else maxOf(inputDb, noise - NOISE_FALL_DB_PER_SECOND * seconds)
                // It rises only between words, so speech doesn't lift it.
                isSpeech -> noise
                else -> minOf(inputDb, noise + NOISE_RISE_DB_PER_SECOND * seconds)
            }
        val floor = noiseDb
        if (isSpeech && (floor == null || inputDb > floor + SPEECH_OVER_NOISE_DB)) {
            quietSeconds = 0f
            val speech = speechDb
            speechDb =
                if (speech == null) inputDb
                else speech + (inputDb - speech) * (seconds / SPEECH_SECONDS).coerceAtMost(1f)
        } else {
            passQuiet(seconds)
        }
        val wanted = wantedGain(floor, speechDb) ?: return
        gainDb =
            when {
                // At first, set the gain at once, so the first words count.
                settling -> wanted
                wanted > gainDb -> minOf(wanted, gainDb + RISE_DB_PER_SECOND * seconds)
                else -> maxOf(wanted, gainDb - FALL_DB_PER_SECOND * seconds)
            }
    }

    /** Counts time without speech, and forgets the speech level after [SPEECH_MEMORY_SECONDS]. */
    private fun passQuiet(seconds: Float) {
        quietSeconds += seconds
        if (quietSeconds >= SPEECH_MEMORY_SECONDS) speechDb = null
    }

    companion object {
        /** Where speech lands after the gain: the average speech window, in dBFS. */
        const val TARGET_DB = -26f

        /** The gain never lifts the noise floor above this, as in WebRTC's AGC2. */
        const val NOISE_CEILING_DB = -50f
        const val MAX_GAIN_DB = 30f

        /** How long without speech before the speech level is forgotten. */
        const val SPEECH_MEMORY_SECONDS = 10f

        /** Peaks are held below this, about −1 dBFS. */
        private const val LIMIT = 0.9f
        private const val DIGITAL_SILENCE_DB = -90f
        private const val STARTUP_SECONDS = 0.2f
        private const val NOISE_FALL_DB_PER_SECOND = 10f
        private const val NOISE_RISE_DB_PER_SECOND = 3f
        private const val SPEECH_OVER_NOISE_DB = 6f
        private const val SPEECH_SECONDS = 1.5f
        private const val SETTLE_SECONDS = 0.5f
        private const val RISE_DB_PER_SECOND = 6f
        private const val FALL_DB_PER_SECOND = 20f

        /**
         * The gain that brings [speechDb] to the target, held down by the noise floor, or null when
         * neither is known yet.
         */
        fun wantedGain(noiseDb: Float?, speechDb: Float?): Float? {
            val forNoise = noiseDb?.let { NOISE_CEILING_DB - it }
            val forSpeech = speechDb?.let { TARGET_DB - it }
            val wanted =
                if (forNoise != null && forSpeech != null) minOf(forNoise, forSpeech)
                else forNoise ?: forSpeech ?: return null
            return wanted.coerceIn(0f, MAX_GAIN_DB)
        }
    }
}

private fun decibelsToGain(decibels: Float): Float = BASE.pow(decibels / DB_PER_AMPLITUDE).toFloat()

private fun gainToDecibels(gain: Float): Float =
    (DB_PER_AMPLITUDE * log10(gain.toDouble())).toFloat()

private const val BASE = 10.0
private const val DB_PER_AMPLITUDE = 20.0

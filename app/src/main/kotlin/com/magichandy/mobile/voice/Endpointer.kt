package com.magichandy.mobile.voice

/**
 * Where speech started and ended, in samples from the start of listening. A [isShort] span is
 * shorter than a word: checked for a spoken Stop, never sent to chat.
 */
data class Span(val start: Long, val end: Long, val isShort: Boolean = false)

/**
 * Turns the speech detector's probability for each window into spans of speech, as Silero's own
 * iterator does: speech starts at the first window at [threshold] or above, and ends after
 * [silenceSeconds] below [threshold] − [HYSTERESIS], so the quiet end of a word doesn't end the
 * turn.
 * - A span shorter than [minShortSeconds] is a click and is dropped.
 * - One shorter than [minSpeechSeconds] comes back as short, because a sharp "Stop!" can be that
 *   brief: voice input checks it for Stop only.
 * - One that reaches [maxSpeechSeconds] is cut there, at its last pause if it has one.
 */
class Endpointer(
    private val threshold: Float,
    silenceSeconds: Float,
    private val window: Int,
    minSpeechSeconds: Float = MIN_SPEECH_SECONDS,
    maxSpeechSeconds: Float = MAX_SPEECH_SECONDS,
    minShortSeconds: Float = MIN_SHORT_SECONDS,
) {
    private val silence = samples(silenceSeconds)
    private val minSpeech = samples(minSpeechSeconds)
    private val maxSpeech = samples(maxSpeechSeconds)
    private val minShort = samples(minShortSeconds)
    private var position = 0L
    private var start = 0L
    private var quietSince: Long? = null

    /** True from the first window of speech until its span ends. */
    var isSpeaking = false
        private set

    /** Takes the next window's probability and returns the span that just ended, if one did. */
    fun accept(probability: Float): Span? {
        val at = position
        position += window
        if (!isSpeaking) {
            if (probability >= threshold) {
                isSpeaking = true
                start = at
                quietSince = null
            }
            return null
        }
        if (probability >= threshold) {
            quietSince = null
        } else if (probability < threshold - HYSTERESIS && quietSince == null) {
            quietSince = at
        }
        val quiet = quietSince
        return when {
            quiet != null && position - quiet >= silence -> end(quiet)
            position - start < maxSpeech -> null
            quiet != null -> end(quiet)
            else ->
                end(position).also {
                    isSpeaking = true
                    start = position
                }
        }
    }

    /** Ends any speech in progress, as when listening stops. */
    fun flush(): Span? = if (isSpeaking) end(quietSince ?: position) else null

    private fun end(at: Long): Span? {
        isSpeaking = false
        quietSince = null
        val length = at - start
        return when {
            length >= minSpeech -> Span(start, at)
            length >= minShort -> Span(start, at, isShort = true)
            else -> null
        }
    }

    private companion object {
        /** Speech carries on until the probability falls this far below the threshold. */
        const val HYSTERESIS = 0.15f
        const val MIN_SPEECH_SECONDS = 0.25f
        const val MIN_SHORT_SECONDS = 0.1f

        /** A turn longer than this is cut, so a long speech still gets transcribed. */
        const val MAX_SPEECH_SECONDS = 20f

        fun samples(seconds: Float): Long = (seconds * SAMPLE_RATE).toLong()
    }
}

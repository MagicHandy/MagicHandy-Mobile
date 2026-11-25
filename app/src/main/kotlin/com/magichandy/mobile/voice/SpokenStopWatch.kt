package com.magichandy.mobile.voice

import kotlin.math.min

/**
 * Says when to check the latest audio for a spoken Stop while someone speaks, so Stop doesn't wait
 * for the pause that ends a turn, or for a long turn to finish (docs/voice-design.md §6).
 *
 * The checks start as soon as the detector's confidence reaches [STOP_THRESHOLD], or the turn's own
 * threshold if that's lower, so a stricter "Speech starts at" can't hide a quiet Stop. From then,
 * and through the pause before the turn ends, [accept] asks for a check of the last [TAIL_SECONDS]
 * every [EVERY_SECONDS]. These values are fixed: the hidden settings don't change them.
 */
class SpokenStopWatch(turnThreshold: Float, private val window: Int) {
    private val heard = Endpointer(min(STOP_THRESHOLD, turnThreshold), PAUSE_SECONDS, window)
    private val every = (EVERY_SECONDS * SAMPLE_RATE).toLong()
    private var sinceCheck = 0L
    private var isActive = false

    /**
     * Takes the next window's [probability] and whether a turn is in progress, and returns true
     * when the last [TAIL_SECONDS] should be checked now.
     */
    fun accept(probability: Float, isTurnSpeaking: Boolean): Boolean {
        heard.accept(probability)
        val active = heard.isSpeaking || isTurnSpeaking
        if (active && !isActive) sinceCheck = 0
        isActive = active
        if (!active) return false
        sinceCheck += window
        if (sinceCheck < every) return false
        sinceCheck = 0
        return true
    }

    companion object {
        /** The confidence where the checks start, unless the turn's threshold is lower. */
        const val STOP_THRESHOLD = 0.5f

        /** How much of the latest audio each check hears. */
        const val TAIL_SECONDS = 1.5f

        /** How often the checks run while someone speaks. */
        const val EVERY_SECONDS = 0.4f

        /** The checks carry on through a pause this long, as a turn's speech does. */
        private const val PAUSE_SECONDS = 0.6f

        /** [TAIL_SECONDS] in samples. */
        val tailSamples: Long = (TAIL_SECONDS * SAMPLE_RATE).toLong()
    }
}

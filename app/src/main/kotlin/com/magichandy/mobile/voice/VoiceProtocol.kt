package com.magichandy.mobile.voice

import android.os.Bundle
import android.os.Message
import com.magichandy.mobile.core.api.VoiceEnd

/**
 * The messages the voice process sends the main process through [VoiceLinkService]
 * (docs/voice-design.md §8). Each carries its listening session in [Message.arg1], and the main
 * process ignores a session that isn't the current one, apart from a spoken Stop, which always
 * stops.
 */
internal object VoiceProtocol {
    /** The voice process connected; [Message.replyTo] lets the main process watch it. */
    const val HELLO = 1

    /** The model loaded and the microphone is recording. */
    const val LISTENING = 2

    /** The meter: [LOUDNESS], [HEARING] and, when levels are shown, [READOUT]. */
    const val LEVEL = 3

    /** A spoken Stop. The main process calls `emergencyStop` before anything else. */
    const val SPOKEN_STOP = 4

    /** A turn to send to chat, in [TEXT]. */
    const val TURN = 5

    /** Listening ended; [Message.arg2] is the [VoiceEnd]'s ordinal. */
    const val ENDED = 6

    const val LOUDNESS = "loudness"
    const val HEARING = "hearing"
    const val READOUT = "readout"
    const val TEXT = "text"

    fun message(what: Int, session: Int, arg2: Int = 0, data: Bundle? = null): Message =
        Message.obtain(null, what, session, arg2).also { if (data != null) it.data = data }

    fun level(session: Int, loudness: Float, isHearing: Boolean, readout: String?): Message =
        message(
            LEVEL,
            session,
            data =
                Bundle().apply {
                    putFloat(LOUDNESS, loudness)
                    putBoolean(HEARING, isHearing)
                    if (readout != null) putString(READOUT, readout)
                },
        )

    const val LAST_SPEECH_MILLIS = "lastSpeechElapsedMillis"

    fun spokenStop(session: Int, lastSpeechMillis: Long): Message =
        message(
            SPOKEN_STOP,
            session,
            data = Bundle().apply { putLong(LAST_SPEECH_MILLIS, lastSpeechMillis) },
        )

    fun turn(session: Int, text: String): Message =
        message(TURN, session, data = Bundle().apply { putString(TEXT, text) })

    fun ended(session: Int, reason: VoiceEnd): Message = message(ENDED, session, reason.ordinal)

    fun reason(message: Message): VoiceEnd =
        VoiceEnd.entries.getOrNull(message.arg2) ?: VoiceEnd.ModelFailed
}

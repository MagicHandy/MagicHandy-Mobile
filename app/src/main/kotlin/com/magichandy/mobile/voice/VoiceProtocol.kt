package com.magichandy.mobile.voice

import android.os.Bundle
import android.os.Message

/** Why listening ended. */
enum class VoiceEnd {
    /** End was tapped: in Chat, on the chip, or in the notification. */
    User,

    /** A Stop, spoken or not. Nothing heard after it can reach chat. */
    Stop,

    /** 15 minutes without speech while nothing played. */
    Quiet,

    /** Android gave the microphone to a call or another app, or its privacy switch turned off. */
    MicTaken,

    /** Microphone access was removed, or Android refused to start listening. */
    MicDenied,

    /** Reading the microphone failed. */
    MicFailed,

    /** The speech model's files were missing or damaged. */
    ModelMissing,

    /** The speech model failed to load, or ran out of memory. */
    ModelFailed,
}

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

    fun turn(session: Int, text: String): Message =
        message(TURN, session, data = Bundle().apply { putString(TEXT, text) })

    fun ended(session: Int, reason: VoiceEnd): Message = message(ENDED, session, reason.ordinal)

    fun reason(message: Message): VoiceEnd =
        VoiceEnd.entries.getOrNull(message.arg2) ?: VoiceEnd.ModelFailed
}

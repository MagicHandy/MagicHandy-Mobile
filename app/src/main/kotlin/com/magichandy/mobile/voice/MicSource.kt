package com.magichandy.mobile.voice

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder

/**
 * Which of Android's microphone paths to record from. Each applies different processing, and the
 * Android CDD sets the level of two of them: a 90 dB SPL tone reads −22 dBFS on [Speech] and about
 * −40 dBFS on [Raw], so ordinary speech a metre away arrives near −52 and −70 dBFS. Voice input
 * uses [Standard], or [EchoCancel] while TalkBack is on; the others are hidden settings.
 */
enum class MicSource(val androidSource: Int) {
    /** What most apps record, and sherpa-onnx's and Home Assistant's Android apps use. */
    Standard(MediaRecorder.AudioSource.MIC),

    /** Android's speech-recognition path: by rule no automatic gain or noise suppression. */
    Speech(MediaRecorder.AudioSource.VOICE_RECOGNITION),

    /**
     * The call path: echo cancellation, noise suppression and the phone's own gain control. Its
     * echo cancellation removes the phone's own speech, such as TalkBack reading a reply.
     */
    EchoCancel(MediaRecorder.AudioSource.VOICE_COMMUNICATION),

    /** As unprocessed as the phone allows, and quieter still. */
    Raw(MediaRecorder.AudioSource.UNPROCESSED);

    /** This path, or [Standard] when it's [Raw] and the phone doesn't offer unprocessed audio. */
    fun onThisPhone(context: Context): MicSource =
        if (
            this == Raw &&
                context
                    .getSystemService(AudioManager::class.java)
                    .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) != "true"
        ) {
            Standard
        } else {
            this
        }
}

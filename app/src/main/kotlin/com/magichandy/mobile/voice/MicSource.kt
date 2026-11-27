package com.magichandy.mobile.voice

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import com.magichandy.mobile.core.api.MicSource

/** The Android recording preset for the selected processing mode. */
val MicSource.androidSource: Int
    get() =
        when (this) {
            MicSource.Standard -> MediaRecorder.AudioSource.MIC
            MicSource.Speech -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            MicSource.EchoCancel -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            MicSource.Raw -> MediaRecorder.AudioSource.UNPROCESSED
        }

/** This path, or [Standard] when it's [Raw] and the phone doesn't offer unprocessed audio. */
fun MicSource.onThisPhone(context: Context): MicSource =
    if (
        this == MicSource.Raw &&
            context
                .getSystemService(AudioManager::class.java)
                .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) != "true"
    ) {
        MicSource.Standard
    } else {
        this
    }

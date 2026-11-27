package com.magichandy.mobile.voice

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import com.magichandy.mobile.MagicHandyApp
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.StopSource

/**
 * The main process's end of voice input's link (docs/voice-design.md §8). The voice process binds
 * here while it listens, which keeps this process, and with it motion and Stop, as important as the
 * voice service.
 *
 * Messages arrive on a thread of their own, so a busy screen can't delay a spoken Stop, which goes
 * straight to [com.magichandy.mobile.core.api.MagicHandyCore.emergencyStop] before anything else
 * sees it (`AGENTS.md`).
 */
class VoiceLinkService : Service() {
    private val thread = HandlerThread("Voice link")
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread.start()
        val app = application as MagicHandyApp
        messenger =
            Messenger(
                Handler(thread.looper) { message ->
                    route(message, app.core::emergencyStop, app.voice::receive)
                    if (
                        message.what == VoiceProtocol.SPOKEN_STOP &&
                            resources.getBoolean(R.bool.voice_hidden_settings)
                    ) {
                        val lastSpeech = message.data.getLong(VoiceProtocol.LAST_SPEECH_MILLIS)
                        val elapsed = SystemClock.elapsedRealtime()
                        if (lastSpeech in 1..elapsed)
                            Log.i(
                                "MagicHandyVoiceQA",
                                "Last voiced audio to core Stop: " + (elapsed - lastSpeech) + " ms",
                            )
                    }
                    true
                }
            )
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    internal companion object {
        /** A spoken Stop goes to [stop] first, directly; then every message goes to [forward]. */
        fun route(message: Message, stop: (StopSource) -> Unit, forward: (Message) -> Unit) {
            if (message.what == VoiceProtocol.SPOKEN_STOP) stop(StopSource.SpokenStop)
            forward(message)
        }
    }
}

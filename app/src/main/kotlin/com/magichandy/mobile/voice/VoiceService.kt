package com.magichandy.mobile.voice

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.magichandy.mobile.MainActivity
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.VoiceConfig
import com.magichandy.mobile.core.api.VoiceEnd
import com.magichandy.mobile.core.api.VoiceModel
import java.io.File

/**
 * Listens in its own process, `:voice`, as a microphone foreground service (docs/voice-design.md
 * §7), so running out of memory or a native crash ends voice input without touching the process
 * that owns motion and Stop.
 *
 * The main process starts it on the tap, while the app is on screen, and it goes to the foreground
 * at once, before the model loads or recording starts. It binds back to [VoiceLinkService], which
 * keeps the main process as important as this service while it listens, and reports through it. It
 * never starts listening by itself: when Android restarts it, it finds nothing to do and stops.
 */
class VoiceService : Service() {
    private val main = Handler(Looper.getMainLooper())

    /** Only so the main process can tell when this process dies: nothing is sent to it. */
    private val watched = Messenger(main)
    private val link = Link()
    private val measurements by lazy {
        if (resources.getBoolean(R.bool.voice_hidden_settings)) VoiceQaMeasurements(this) else null
    }
    private var session: VoiceSession? = null
    private var sessionId = 0
    private var isBound = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_START -> start(intent)
            intent?.action == ACTION_END && session != null -> session?.end(VoiceEnd.User)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.end(VoiceEnd.User)
        measurements?.finish()
        session = null
        if (isBound) {
            isBound = false
            unbindService(link)
        }
        super.onDestroy()
    }

    private fun start(intent: Intent) {
        val id = intent.getIntExtra(EXTRA_SESSION, 0)
        val model =
            VoiceModel.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODEL) }
                ?: VoiceModel.Compact
        val config = VoiceConfig.from(intent)
        if (!isBound) {
            isBound =
                bindService(Intent(this, VoiceLinkService::class.java), link, BIND_AUTO_CREATE)
        }
        if (!goForeground()) {
            link.send(VoiceProtocol.ended(id, VoiceEnd.MicDenied))
            if (session == null) stopSelf()
            return
        }
        val previous = session
        previous?.end(VoiceEnd.User)
        val next =
            VoiceSession(
                this,
                ModelStore(modelFolder(this)),
                model,
                config,
                config.source.onThisPhone(this),
                Reports(id),
            )
        session = next
        sessionId = id
        measurements?.begin(model)
        next.start(after = previous)
    }

    /** Goes to the foreground as a microphone service; false when Android refuses. */
    private fun goForeground(): Boolean =
        try {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            true
        } catch (refused: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Android refused to start listening", refused)
            false
        } catch (refused: SecurityException) {
            Log.w(TAG, "Listening needs microphone access", refused)
            false
        }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                        CHANNEL,
                        getString(R.string.voice_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply {
                        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                        setShowBadge(false)
                    }
            )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val chat =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags,
            )
        val end =
            PendingIntent.getService(
                this,
                0,
                Intent(this, VoiceService::class.java).setAction(ACTION_END),
                flags,
            )
        fun base() =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_star)
                .setColor(getColor(R.color.brand_accent))
                .setShowWhen(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(chat)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(
                    Notification.Action.Builder(null, getString(R.string.voice_end), end).build()
                )
        // The lock screen's public version says only that the app is active.
        val public =
            base()
                .setContentTitle(getString(R.string.platform_active))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        return base()
            .setContentTitle(getString(R.string.voice_notice_title))
            .setContentText(getString(R.string.voice_notice_text))
            .setPublicVersion(public)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    /** On the main thread: leaves the foreground once the current session has ended. */
    private fun finished(id: Int) {
        if (session == null || sessionId != id) return
        session = null
        measurements?.finish()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** One session's reports to the main process. */
    private inner class Reports(private val id: Int) : VoiceSession.Events {
        override fun listening() = link.send(VoiceProtocol.message(VoiceProtocol.LISTENING, id))

        override fun level(loudness: Float, isHearing: Boolean, readout: String?) =
            link.send(VoiceProtocol.level(id, loudness, isHearing, readout))

        override fun spokenStop(lastSpeechElapsedMillis: Long) =
            link.send(VoiceProtocol.spokenStop(id, lastSpeechElapsedMillis))

        override fun turn(text: String) = link.send(VoiceProtocol.turn(id, text))

        override fun ended(reason: VoiceEnd) {
            link.send(VoiceProtocol.ended(id, reason))
            main.post { finished(id) }
        }
    }

    /**
     * The link to the main process. Reports wait here until it connects, apart from the meter's,
     * which only matter live. If the main process goes, so does listening, since a spoken Stop
     * could no longer stop anything.
     */
    private inner class Link : ServiceConnection {
        private val lock = Any()
        private var messenger: Messenger? = null
        private val waiting = ArrayDeque<Message>()

        fun send(message: Message) {
            val target =
                synchronized(lock) {
                    val connected = messenger
                    if (connected == null && message.what != VoiceProtocol.LEVEL) {
                        waiting.addLast(message)
                    }
                    connected
                } ?: return
            deliver(target, message)
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val target = Messenger(binder ?: return)
            val hello =
                VoiceProtocol.message(VoiceProtocol.HELLO, sessionId).also {
                    it.replyTo = watched
                }
            val queued =
                synchronized(lock) {
                    messenger = target
                    waiting.toList().also { waiting.clear() }
                }
            deliver(target, hello)
            queued.forEach { deliver(target, it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { messenger = null }
            main.post { session?.end(VoiceEnd.User) }
        }

        override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)

        private fun deliver(target: Messenger, message: Message) {
            try {
                target.send(message)
            } catch (gone: RemoteException) {
                Log.w(TAG, "The main process is gone", gone)
            }
        }
    }

    companion object {
        const val ACTION_START = "com.magichandy.mobile.voice.START"
        const val ACTION_END = "com.magichandy.mobile.voice.END"
        const val EXTRA_SESSION = "voice.session"
        const val EXTRA_MODEL = "voice.model"
        private const val CHANNEL = "voice"
        private const val NOTIFICATION_ID = 918
        private const val TAG = "Voice"

        /** Where the speech models live, for both processes. Never backed up. */
        fun modelFolder(context: Context): File = File(context.noBackupFilesDir, "voice")
    }
}

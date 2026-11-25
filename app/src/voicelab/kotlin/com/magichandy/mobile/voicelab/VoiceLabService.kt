package com.magichandy.mobile.voicelab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.magichandy.mobile.R

/**
 * Keeps the process in the foreground with the microphone while hands-free listening runs, so it
 * carries on with the screen off. It holds no logic: the [VoiceLab] does the listening.
 */
class VoiceLabService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            VoiceLab.get(this).stopListening()
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Voice test", NotificationManager.IMPORTANCE_LOW)
        )
        val end =
            PendingIntent.getService(
                this,
                0,
                Intent(this, VoiceLabService::class.java).setAction(ACTION_END),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, VoiceLabActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_star)
                .setContentTitle("Voice test is listening")
                .setContentText("Nothing is sent and no device moves.")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(0, "End test", end)
                .build()
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            START_NOT_STICKY
        } catch (_: RuntimeException) {
            // Android refused, as when the page wasn't on screen; listening stops with it.
            stopSelf()
            START_NOT_STICKY
        }
    }

    companion object {
        private const val CHANNEL = "voice_lab"
        private const val NOTIFICATION_ID = 7_301
        private const val ACTION_END = "com.magichandy.mobile.voicelab.END"

        /** Starts the service; false when Android doesn't allow it now. */
        fun start(context: Context): Boolean =
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, VoiceLabService::class.java),
                )
                true
            } catch (_: RuntimeException) {
                false
            }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceLabService::class.java))
        }
    }
}

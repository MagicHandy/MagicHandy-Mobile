package com.magichandy.mobile.platform

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import com.magichandy.mobile.MainActivity
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.*

internal class CoreNotice(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                    channel,
                    context.getString(R.string.platform_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    setShowBadge(false)
                }
        )
    }

    fun privateText(state: AppState): String = buildList {
        val names =
            state.devices.devices
                .filter {
                    it.status in
                        setOf(DeviceStatus.Connected, DeviceStatus.Paused, DeviceStatus.Playing)
                }
                .joinToString(", ") { it.name }
        if (names.isNotEmpty()) add(names)
        state.run.commandedPace?.let { add(context.getString(R.string.platform_pace, it.value)) }
        state.autopilot.nextChangeIn?.let {
            add(context.getString(R.string.platform_next, it.inWholeSeconds.coerceAtLeast(0)))
        }
    }
        .joinToString(" · ")

    fun build(state: AppState) = build(privateText(state))

    private fun build(text: String): Notification {
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop =
            PendingIntent.getBroadcast(
                context,
                2,
                Intent(context, StopActionReceiver::class.java).setAction(stopAction),
                immutable,
            )
        val live =
            PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java)
                    .setAction(openLive)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                immutable,
            )
        fun base() =
            Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_star)
                .setColor(context.getColor(R.color.brand_accent))
                .setContentTitle(context.getString(R.string.platform_active))
                .setShowWhen(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(live)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(
                    Notification.Action.Builder(
                            null,
                            context.getString(R.string.platform_stop),
                            stop,
                        )
                        .build()
                )
        val public = base().setVisibility(Notification.VISIBILITY_PUBLIC).build()
        return base()
            .setContentText(text)
            .setPublicVersion(public)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    fun update(text: String) {
        if (
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        )
            manager.notify(id, build(text))
    }

    companion object {
        const val id = 917
        const val channel = "running"
        const val openLive = "com.magichandy.mobile.OPEN_LIVE"
        const val stopAction = "com.magichandy.mobile.STOP"
    }
}

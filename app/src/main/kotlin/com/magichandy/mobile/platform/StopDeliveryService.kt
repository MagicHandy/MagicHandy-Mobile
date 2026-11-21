package com.magichandy.mobile.platform

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.magichandy.mobile.core.api.MagicHandyCore
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The drivers have independent Stop deadlines. The extra window covers the durable journal update.
 */
internal suspend fun MagicHandyCore.finishStopWindow() {
    withTimeoutOrNull(8.seconds) { awaitEmergencyStop() }
}

/** Retains a cold tile-triggered Stop without granting the app foreground ownership for motion. */
class StopDeliveryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var finishing: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val core = (application as CoreOwner).core
        try {
            val type =
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                else 0
            ServiceCompat.startForeground(
                this,
                CoreNotice.id + 1,
                CoreNotice(this).build(core.state.value),
                type,
            )
        } catch (_: IllegalStateException) {
            Log.w("MagicHandy", "Android declined the temporary Stop delivery service")
            stopSelf(startId)
            return START_NOT_STICKY
        } catch (_: SecurityException) {
            Log.w("MagicHandy", "Android declined the temporary Stop delivery service")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        finishing?.cancel()
        finishing = scope.launch {
            try {
                core.finishStopWindow()
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    internal companion object {
        fun retain(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, StopDeliveryService::class.java),
                )
            } catch (_: IllegalStateException) {
                Log.w("MagicHandy", "Android declined the temporary Stop delivery service")
            } catch (_: SecurityException) {
                Log.w("MagicHandy", "Android declined the temporary Stop delivery service")
            }
        }
    }
}

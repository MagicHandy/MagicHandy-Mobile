package com.magichandy.mobile.platform

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.ports.RunHost
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal interface CoreOwner {
    val core: MagicHandyCore
    val host: AndroidHost
}

/** Admission waits for startForeground to succeed. A visible activity owns each first start. */
class AndroidHost internal constructor(private val context: Context) : RunHost {
    @Volatile var visible = false
    private var promoted = false
    private var pending: CompletableDeferred<Boolean>? = null

    override suspend fun ensureRunning(): Boolean {
        val wait =
            synchronized(this) {
                if (promoted) return true
                if (!visible) return false
                pending
                    ?: CompletableDeferred<Boolean>().also { request ->
                        pending = request
                        try {
                            ContextCompat.startForegroundService(
                                context,
                                Intent(context, CoreService::class.java),
                            )
                        } catch (_: IllegalStateException) {
                            finished(false)
                        } catch (_: SecurityException) {
                            finished(false)
                        }
                    }
            }
        return withTimeoutOrNull(5.seconds) { wait.await() }
            ?: synchronized(this) {
                if (pending === wait) finished(false)
                false
            }
    }

    @Synchronized
    fun finished(success: Boolean) {
        promoted = success
        pending?.complete(success)
        pending = null
    }

    fun access(core: MagicHandyCore) {
        val notices = context.getSystemService(NotificationManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        core.updateSystemAccess(
            notices.areNotificationsEnabled(),
            power.isIgnoringBatteryOptimizations(context.packageName),
        )
    }
}

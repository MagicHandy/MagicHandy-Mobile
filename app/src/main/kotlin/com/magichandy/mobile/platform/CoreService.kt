package com.magichandy.mobile.platform

import android.annotation.SuppressLint
import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.magichandy.mobile.core.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal val AppState.needsHost
    get() =
        run.phase != RunPhase.Idle ||
            autopilot.status != AutopilotStatus.Off ||
            devices.devices.any {
                it.status in
                    setOf(
                        DeviceStatus.Connecting,
                        DeviceStatus.Connected,
                        DeviceStatus.Playing,
                        DeviceStatus.Paused,
                    )
            }

class CoreService : Service() {
    private val owner
        get() = application as CoreOwner

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wake: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val core = owner.core
        val notices = CoreNotice(this)
        try {
            ServiceCompat.startForeground(
                this,
                CoreNotice.id,
                notices.build(core.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (_: SecurityException) {
            owner.host.finished(false)
            stopSelf(startId)
            return START_NOT_STICKY
        } catch (_: IllegalStateException) {
            owner.host.finished(false)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        owner.host.finished(true)
        observer?.cancel()
        observer = scope.launch {
            core.state
                .map { state -> serviceView(state, notices.privateText(state)) }
                .distinctUntilChanged()
                .collect { view ->
                    if (!view.active) {
                        owner.host.finished(false)
                        stopSelf(startId)
                    } else {
                        setWake(view.playing)
                        notices.update(view.text)
                    }
                }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("WakelockTimeout") // Released on every idle transition and in onDestroy.
    private fun setWake(playing: Boolean) {
        if (!playing) {
            wake?.cancel()
            wake = null
        } else if (wake == null) {
            wake =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val lock =
                        getSystemService(PowerManager::class.java)
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MagicHandy:playback")
                    lock.acquire()
                    try {
                        awaitCancellation()
                    } finally {
                        lock.release()
                    }
                }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        owner.core.emergencyStop(StopSource.TaskRemoved)
        stopSelf()
    }

    override fun onDestroy() {
        owner.host.finished(false)
        scope.cancel()
        setWake(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

class StopActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CoreNotice.stopAction)
            (context.applicationContext as CoreOwner).core.emergencyStop(StopSource.Notification)
    }
}

/**
 * What the service shows and holds for [state]. A change re-posts the notification, and that
 * includes Android starting to allow notifications: one posted before then stays hidden otherwise.
 */
internal data class ServiceView(
    val active: Boolean,
    val playing: Boolean,
    val text: String,
    val canNotify: Boolean,
)

internal fun serviceView(state: AppState, text: String): ServiceView =
    ServiceView(
        active = state.needsHost,
        playing = state.run.phase in setOf(RunPhase.Playing, RunPhase.Edging),
        text = text,
        canNotify = state.app.notificationsAllowed,
    )

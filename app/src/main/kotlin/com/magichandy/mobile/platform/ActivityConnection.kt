package com.magichandy.mobile.platform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.magichandy.mobile.MagicHandyApp
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.api.VoiceControls
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Asks for radio access only at explicit Bluetooth setup and for notifications on first connection.
 */
internal class ActivityConnection(
    private val activity: ComponentActivity,
    private val app: MagicHandyApp,
    private val simulated: Boolean,
) : MagicHandyCore by app.core {
    override val voice: VoiceControls
        get() = app.voice

    private var waiting: UserIntent.StartScan? = null
    private val bluetooth =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            app.refreshRadio()
            waiting?.let(app.core::dispatch)
            waiting = null
        }
    private val notifications =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            app.host.access(app.core)
        }

    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (Build.VERSION.SDK_INT < 33) return@repeatOnLifecycle
                state
                    .map { s ->
                        !s.app.hasAskedForNotifications &&
                            s.devices.devices.any {
                                it.status == DeviceStatus.Connected ||
                                    it.status == DeviceStatus.Playing
                            }
                    }
                    .distinctUntilChanged()
                    .collect { due ->
                        if (
                            due &&
                                activity.checkSelfPermission(
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            app.core.notificationPermissionRequested()
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
            }
        }
    }

    override fun dispatch(intent: UserIntent) {
        if (intent == UserIntent.StopScan) waiting = null
        if (
            intent is UserIntent.StartScan &&
                !simulated &&
                state.value.firstRun.hasAcknowledgedAdultsOnly &&
                (intent.family != DeviceFamily.Coyote ||
                    state.value.devices.hasAcknowledgedCoyoteNotes)
        ) {
            val permissions =
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            if (
                permissions.any {
                    activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                }
            ) {
                if (waiting == null) {
                    waiting = intent
                    bluetooth.launch(permissions)
                }
                return
            }
        }
        app.core.dispatch(intent)
    }
}

package com.magichandy.mobile.ui

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.magichandy.mobile.ui.settings.SystemAccess
import com.magichandy.mobile.ui.settings.SystemLinks

/**
 * Reads what Android allows the app: notifications (for the lock-screen Stop) and unrestricted
 * battery use. It refreshes whenever the app comes back, since both change in system settings.
 */
@Composable
fun rememberSystemAccess(): SystemAccess {
    val context = LocalContext.current
    var access by remember { mutableStateOf(readAccess(context)) }
    LifecycleResumeEffect(context) {
        access = readAccess(context)
        onPauseOrDispose {}
    }
    return access
}

/** Opens the system screens that grant what [SystemAccess] reports. */
@Composable
fun rememberSystemLinks(): SystemLinks {
    val context = LocalContext.current
    return remember(context) {
        SystemLinks(
            openNotificationSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            openBatterySettings = {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
    }
}

private fun readAccess(context: Context): SystemAccess {
    val power = context.getSystemService(PowerManager::class.java)
    return SystemAccess(
        areNotificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        isBatteryUnrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true,
    )
}

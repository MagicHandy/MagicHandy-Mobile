package com.magichandy.mobile.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppSettingsState
import com.magichandy.mobile.platform.StopTileService
import com.magichandy.mobile.ui.components.Banner
import com.magichandy.mobile.ui.components.BannerTone
import com.magichandy.mobile.ui.settings.StopTileRequest
import com.magichandy.mobile.ui.settings.SystemAccess
import com.magichandy.mobile.ui.settings.SystemLinks

/**
 * What Android allows the app, from the core's latest snapshot. The platform takes a new snapshot
 * each time the app comes to the foreground, so it catches changes made in system settings.
 */
fun systemAccess(app: AppSettingsState): SystemAccess =
    SystemAccess(
        areNotificationsAllowed = app.notificationsAllowed,
        isBatteryUnrestricted = app.isBatteryUnrestricted,
        // Android 13 and later ask first, so off means refused only once the app has asked.
        // Earlier versions never ask, so off always means someone turned notifications off.
        areNotificationsRefused =
            app.hasSystemAccessSnapshot &&
                !app.notificationsAllowed &&
                (app.hasAskedForNotifications ||
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU),
    )

/** Opens the system screens that grant what [SystemAccess] reports, and asks for the Stop tile. */
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
            requestStopTile =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    { onResult -> requestStopTile(context, onResult) }
                } else null,
        )
    }
}

/** Asks Android to add the Stop tile to Quick Settings. The system shows its own confirmation. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun requestStopTile(context: Context, onResult: (StopTileRequest) -> Unit) {
    val manager = context.getSystemService(StatusBarManager::class.java)
    if (manager == null) {
        onResult(StopTileRequest.Failed)
        return
    }
    manager.requestAddTileService(
        ComponentName(context, StopTileService::class.java),
        context.getString(R.string.platform_tile),
        Icon.createWithResource(context, R.drawable.ic_tile_stop),
        context.mainExecutor,
    ) { code ->
        onResult(
            when (code) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> StopTileRequest.Added
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> StopTileRequest.Declined
                else -> StopTileRequest.Failed
            }
        )
    }
}

/** Warns that the lock-screen Stop is off because notifications were refused (board V-System). */
@Composable
fun NotificationsRefusedBanner(onOpenSettings: () -> Unit) {
    Banner(
        tone = BannerTone.Warn,
        title = stringResource(R.string.notifications_blocked_title),
        body = stringResource(R.string.notifications_blocked_body),
        actionLabel = stringResource(R.string.notifications_open_settings),
        onAction = onOpenSettings,
    )
}

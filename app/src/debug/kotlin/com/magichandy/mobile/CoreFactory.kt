package com.magichandy.mobile

import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.createMagicHandyCore
import com.magichandy.mobile.platform.storage.PlatformDatabase
import com.magichandy.mobile.platform.storage.RoomStores
import com.magichandy.mobile.transport.simulated.simulatedDependencies
import kotlinx.coroutines.job

/**
 * The debug model and devices run in process. A separate no-backup database keeps the person's
 * setup, personas, chats and optional memories across debug process restarts.
 */
internal fun createCore(app: MagicHandyApp): MagicHandyCore {
    val db = PlatformDatabase.open(app, "simulated.db")
    val stores = RoomStores(db, app.processScope)
    app.processScope.coroutineContext.job.invokeOnCompletion { db.close() }
    return createMagicHandyCore(
        simulatedDependencies(app.processScope)
            .copy(
                chats = stores.chats,
                settings = stores.settings,
                runHost = app.host,
                androidApi = android.os.Build.VERSION.SDK_INT,
                versionName =
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty(),
            )
    )
}

internal const val simulatedDevices = true

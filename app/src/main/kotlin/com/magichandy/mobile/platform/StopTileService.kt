package com.magichandy.mobile.platform

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.magichandy.mobile.core.api.StopSource

/** The tile remains usable for detached cloud recovery even when no activity is visible. */
class StopTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        (application as CoreOwner).core.emergencyStop(StopSource.QuickSettingsTile)
    }
}

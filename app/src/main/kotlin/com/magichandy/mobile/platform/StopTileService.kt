package com.magichandy.mobile.platform

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.magichandy.mobile.core.api.StopSource

/** The tile remains usable for detached cloud recovery even when no activity is visible. */
class StopTileService : TileService() {
    override fun onTileAdded() {
        super.onTileAdded()
        (application as CoreOwner).core.updateStopTileAdded(true)
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        (application as CoreOwner).core.updateStopTileAdded(false)
    }

    override fun onStartListening() {
        super.onStartListening()
        (application as CoreOwner).core.updateStopTileAdded(true)
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

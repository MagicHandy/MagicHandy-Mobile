package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Saved device selection. Credential handles and radio addresses are resolved by the directory. */
public data class SavedDevice(public val ref: DeviceRef, public val settings: DeviceSettings)

/** Device connection boundary. Connecting an owner verifies it and leaves it idle. */
public interface DeviceDirectory : RecoveryStops {
    /** Preconfigured devices available for explicit connection. */
    public val saved: List<SavedDevice>

    /** Actual radio availability. The platform updates it on adapter state changes. */
    public val bluetoothOn: StateFlow<Boolean>
    /** Notify the core about unexpected link loss, including when only Bluetooth drops. */
    public val losses: Flow<DeviceId>

    /**
     * Return a verified, idle owner for exactly [device], or null on failure. Use [ownerScope] for
     * notifications and acknowledgements so they remain independent of ordinary motion and storage.
     */
    public suspend fun connect(device: DeviceId, ownerScope: CoroutineScope): DeviceTransport?

    /** Release a connection after engine Stop and preserve unresolved physical evidence. */
    public suspend fun disconnect(device: DeviceId)
}

package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Saved device selection. Credential handles and radio addresses are resolved by the directory. */
@kotlinx.serialization.Serializable
public data class SavedDevice(
    public val ref: DeviceRef,
    public val settings: DeviceSettings,
    public val address: String? = null,
    public val credential: SecretHandle? = null,
    public val deviceIndex: Int? = null,
    /**
     * Fingerprint of the selected device and feature. A discovery index alone cannot identify
     * hardware.
     */
    public val bridgeIdentity: String? = null,
)

/** Device connection boundary. Connecting an owner verifies it and leaves it idle. */
public interface DeviceDirectory : RecoveryStops {
    /**
     * Fence and stop live shared bridge sessions, including a discovery lease without a selected
     * device.
     */
    public fun stopOpenBridges(epoch: Epoch) {}

    /** Editable durable rows and explicit discovery, when supported by this adapter. */
    public val configuration: DeviceConfiguration?
        get() = null

    /** Preconfigured devices available for explicit connection. */
    public val saved: List<SavedDevice>

    /**
     * Persist one Handy's mounting and publish it in [saved] before returning. False for unknown
     * devices or other families.
     */
    public suspend fun setHandyReversed(device: DeviceId, isReversed: Boolean): Boolean

    /** Actual radio availability. The platform updates it on adapter state changes. */
    public val bluetoothOn: StateFlow<Boolean>
    /** Notify the core about unexpected link loss, including when only Bluetooth drops. */
    public val losses: Flow<DeviceId>
    /** Previously lost saved devices becoming reachable again, when the adapter has an event. */
    public val returns: Flow<DeviceId>
        get() = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Return a verified, idle owner for exactly [device], or null on failure. Use [ownerScope] for
     * notifications and acknowledgements so they remain independent of ordinary motion and storage.
     */
    public suspend fun connect(device: DeviceId, ownerScope: CoroutineScope): DeviceTransport?

    /** Release a connection after engine Stop and preserve unresolved physical evidence. */
    public suspend fun disconnect(device: DeviceId)
}

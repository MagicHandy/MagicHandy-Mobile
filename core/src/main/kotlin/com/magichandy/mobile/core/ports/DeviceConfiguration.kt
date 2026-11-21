package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.IntifaceChoice
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.ScanResult
import com.magichandy.mobile.core.api.ScanResultId
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A discovery-only index and the stable protocol identity used for reconnection. */
public data class BridgeTarget(
    public val deviceIndex: Int,
    public val name: String,
    public val identity: String,
    public val family: DeviceFamily,
    public val actuatorIndex: Int = 0,
    public val descriptor: String = "",
)

/** The saved identity cannot be distinguished in the current bridge device list. */
public class BridgeSelectionRequired : java.io.IOException("Choose a bridge device again")

/** Durable ordered device records. A Bluetooth address or endpoint is private to the adapter. */
public interface DeviceRecords {
    public val rows: StateFlow<List<SavedDevice>>

    public suspend fun save(row: SavedDevice)

    /** Apply one field edit to the latest row. An edit never recreates a removed row. */
    public suspend fun update(id: DeviceId, transform: (SavedDevice) -> SavedDevice) {
        rows.value.firstOrNull { it.ref.id == id }?.let { save(transform(it)) }
    }

    public suspend fun remove(id: DeviceId)
}

/**
 * Explicit discovery only. A result is resolved to a durable reference while that scan is valid.
 */
public interface DeviceConfiguration : DeviceRecords {
    /** Read-only v3 device list at the explicitly supplied WebSocket origin. */
    public suspend fun intifaceVibrators(address: String): List<IntifaceChoice> = emptyList()

    /** Inspect only the supplied bridge. The caller persists identity after explicit selection. */
    public suspend fun bridgeTargets(address: String): List<BridgeTarget> = emptyList()

    public val scanAllowed: Boolean
        get() = true

    public fun scan(family: DeviceFamily): Flow<ScanResult>

    public fun address(result: ScanResultId): String?
}

public fun defaultDeviceSettings(family: DeviceFamily): DeviceSettings =
    when (family) {
        DeviceFamily.LinearActuator -> DeviceSettings.LinearActuator()
        DeviceFamily.Handy -> DeviceSettings.Handy("handy_original", false)
        DeviceFamily.Coyote -> DeviceSettings.Coyote(0, 0)
        DeviceFamily.Vibrator -> DeviceSettings.Vibrator(Percent(40))
        DeviceFamily.AutoblowUltra -> DeviceSettings.AutoblowUltra
        DeviceFamily.VacuGlide -> DeviceSettings.VacuGlide(5..100, false, Duration.parse("15m"))
    }

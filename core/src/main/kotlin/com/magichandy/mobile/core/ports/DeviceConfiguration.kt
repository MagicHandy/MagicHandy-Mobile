package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Durable ordered device records. A Bluetooth address or endpoint is private to the adapter. */
public interface DeviceRecords {
    public val rows: StateFlow<List<SavedDevice>>

    public suspend fun save(row: SavedDevice)

    public suspend fun remove(id: DeviceId)
}

/**
 * Explicit discovery only. A result is resolved to a durable reference while that scan is valid.
 */
public interface DeviceConfiguration : DeviceRecords {
    /** Read-only v3 device list at the explicitly supplied WebSocket origin. */
    public suspend fun intifaceVibrators(address: String): List<IntifaceChoice> = emptyList()

    public val scanAllowed: Boolean
        get() = true

    public fun scan(family: DeviceFamily): Flow<ScanResult>

    public fun address(result: ScanResultId): String?
}

public fun defaultDeviceSettings(family: DeviceFamily): DeviceSettings =
    when (family) {
        DeviceFamily.Handy -> DeviceSettings.Handy("handy_original", false)
        DeviceFamily.Coyote -> DeviceSettings.Coyote(0, 0)
        DeviceFamily.Vibrator -> DeviceSettings.Vibrator(Percent(70))
        DeviceFamily.AutoblowUltra -> DeviceSettings.AutoblowUltra
        DeviceFamily.VacuGlide ->
            DeviceSettings.VacuGlide(5..100, false, kotlin.time.Duration.parse("15m"))
    }

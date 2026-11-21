// Ported from internal/ble/{link,profiles}.go at 9a4c36cf.
package com.magichandy.mobile.transport.ble

import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.ScanResultId
import com.magichandy.mobile.core.ports.SliderReading
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

/** A write was submitted while establishing this generation. Retrying setup could repeat it. */
public class BluetoothSetupWriteFailure(cause: java.io.IOException) :
    java.io.IOException("Notification setup was not confirmed", cause)

/**
 * Write admission expired before any bytes were submitted to the radio. The GATT generation remains
 * usable.
 */
public class BluetoothWriteFenced : java.io.IOException("Bluetooth write fenced before submission")

/** Allowlisted GATT service and characteristic identifiers, with the profile's payload ceiling. */
public enum class GattProfile(
    public val service: String,
    public val write: String,
    public val notify: String,
    public val maxBytes: Int,
) {
    Handy(
        "77834d26-40f7-11ee-be56-0242ac120002",
        "77835032-40f7-11ee-be56-0242ac120002",
        "77835410-40f7-11ee-be56-0242ac120002",
        512,
    ),
    Coyote(
        "0000180c-0000-1000-8000-00805f9b34fb",
        "0000150a-0000-1000-8000-00805f9b34fb",
        "0000150b-0000-1000-8000-00805f9b34fb",
        64,
    ),
}

/** One scoped scan result. IDs become invalid when discovery ends. */
public data class Advertisement(
    public val id: ScanResultId,
    public val name: String,
    public val signalDbm: Int,
)

/** Explicit filtered scan. Cancellation ends scanning. The caller checks Coyote consent first. */
public fun interface BluetoothScanner {
    /** Emit only matching names and service UUIDs for [profile]. */
    public fun scan(profile: GattProfile): Flow<Advertisement>
}

/** Opens a fresh GATT generation for exactly the selected result, with auto-connect disabled. */
public fun interface BluetoothConnector {
    /** Fail within [timeout] and close resources if cancelled before returning the link. */
    public suspend fun connect(
        id: ScanResultId,
        profile: GattProfile,
        timeout: Duration,
    ): BluetoothLink
}

/**
 * One immutable GATT generation. The platform serializes native operations, correlates callbacks
 * and bounds each operation, including reads, by its own deadline. Timeout or uncertain delivery
 * retires the generation. Never deliver its callbacks to the successor.
 */
public interface BluetoothLink {
    /**
     * Best-effort hint for high connection priority during playback and balanced priority while
     * idle. Never fail playback or an acknowledged Stop if Bluetooth permission changes.
     */
    public fun setActive(active: Boolean) {}

    /**
     * Optional physical slider geometry from a verified profile. HSP playback state alone has no
     * position evidence.
     */
    public suspend fun readSliderPosition(admitted: () -> Boolean): SliderReading? = null

    /** Read an advertised battery characteristic on this generation, when available. */
    public suspend fun readBattery(): Percent? = null

    /** Negotiate a real ATT MTU and return the granted value including the three-byte header. */
    public suspend fun requestMtu(bytes: Int): Int

    /** Discover the specified service and return its characteristic UUIDs. */
    public suspend fun discover(service: String): Set<String>

    /** Enable notifications before returning their lossless ordered stream. Fail on disconnect. */
    public suspend fun notifications(characteristic: String): Flow<ByteArray>

    /** Recheck [admitted] immediately before the physical write, after any platform queue wait. */
    public suspend fun write(
        characteristic: String,
        bytes: ByteArray,
        withResponse: Boolean,
        admitted: () -> Boolean,
    )

    /**
     * Submit a Stop ahead of queued reads or normal writes after the outstanding native call
     * settles.
     */
    public suspend fun writeStop(
        characteristic: String,
        bytes: ByteArray,
        withResponse: Boolean,
        admitted: () -> Boolean,
    ) {
        write(characteristic, bytes, withResponse, admitted)
    }

    /** Read a characteristic on this generation through the serialized operation queue. */
    public suspend fun read(characteristic: String): ByteArray

    /**
     * Invalidate queued operations and close the native link immediately. Idempotent and
     * nonblocking.
     */
    public fun retire()
}

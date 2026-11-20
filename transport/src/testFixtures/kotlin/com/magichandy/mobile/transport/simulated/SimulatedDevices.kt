package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.ScanResultId
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.SavedDevice
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import com.magichandy.mobile.transport.ble.BluetoothConnector
import com.magichandy.mobile.transport.handy.HandyBluetoothSession
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Command-side simulator state. VacuGlide retains speed across host death and link loss. */
public data class SimulatedOutput(
    public val speed: Percent = Percent(0),
    public val strengthA: Int = 0,
    public val strengthB: Int = 0,
    public val batches: Int = 0,
    public val stops: Int = 0,
)

/**
 * Debug-only directory for all five device families. No socket, Bluetooth adapter or credentials.
 * Keep this object across simulated process death to preserve the physical device's independent
 * state.
 */
public class SimulatedDevices : DeviceDirectory {
    override val bluetoothOn: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    override val saved: List<SavedDevice> =
        listOf(
            item(
                "handy",
                "The Handy",
                DeviceFamily.Handy,
                ConnectionKind.Bluetooth,
                DeviceSettings.Handy("handy_original", false),
            ),
            item(
                "vibrator",
                "Vibrator",
                DeviceFamily.Vibrator,
                ConnectionKind.IntifaceOnPhone,
                DeviceSettings.Vibrator(Percent(70)),
            ),
            item(
                "coyote",
                "Coyote 3.0",
                DeviceFamily.Coyote,
                ConnectionKind.Bluetooth,
                DeviceSettings.Coyote(0, 0),
            ),
            item(
                "ultra",
                "Autoblow Ultra",
                DeviceFamily.AutoblowUltra,
                ConnectionKind.AutoblowCloud,
                DeviceSettings.AutoblowUltra,
            ),
            item(
                "vacuglide",
                "VacuGlide",
                DeviceFamily.VacuGlide,
                ConnectionKind.AutoblowCloud,
                DeviceSettings.VacuGlide(5..100, false, 15.minutes),
            ),
        )
    private val outputs = MutableStateFlow(saved.associate { it.ref.id to SimulatedOutput() })
    /** Inspect independent device state without going through UI projections. */
    public val output: StateFlow<Map<DeviceId, SimulatedOutput>> = outputs.asStateFlow()
    private val lost = MutableSharedFlow<DeviceId>(extraBufferCapacity = 16)
    override val losses: kotlinx.coroutines.flow.Flow<DeviceId> = lost
    private val unavailable = MutableStateFlow<Set<DeviceId>>(emptySet())
    private val connections = MutableStateFlow<Map<DeviceId, Owner>>(emptyMap())

    private fun item(
        id: String,
        name: String,
        family: DeviceFamily,
        kind: ConnectionKind,
        settings: DeviceSettings,
    ) = SavedDevice(DeviceRef(DeviceId(id), name, family, kind), settings)

    override suspend fun connect(device: DeviceId, ownerScope: CoroutineScope): DeviceTransport? {
        val savedDevice = saved.firstOrNull { it.ref.id == device } ?: return null
        if (device in unavailable.value) return null
        val radio =
            if (savedDevice.ref.family == DeviceFamily.Handy)
                SimulatedHandyLink { rememberStop(device) }
            else null
        val session = radio?.let { link ->
            HandyBluetoothSession.connect(
                savedDevice.ref,
                ScanResultId(device.value),
                BluetoothConnector { _, _, _ -> link },
                ownerScope,
            )
        }
        val owner = Owner(savedDevice, session, radio)
        connections.update { it + (device to owner) }
        return owner
    }

    override suspend fun disconnect(device: DeviceId) {
        connections.value[device]?.session?.disconnect()
        connections.update { it - device }
    }

    /** Drop only this device's link. A persistent-speed motor keeps the last accepted speed. */
    public suspend fun loseLink(id: DeviceId) {
        connections.value[id]?.radio?.retire()
        unavailable.update { it + id }
        connections.update { it - id }
        if (saved.firstOrNull { it.ref.id == id }?.ref?.family != DeviceFamily.VacuGlide) idle(id)
        lost.emit(id)
    }

    /** Restore reachability. The core must still explicitly reconnect or issue a recovery Stop. */
    public fun restoreLink(id: DeviceId) {
        unavailable.update { it - id }
    }

    /** Simulate loss of host-owned connections. Persistent devices continue independently. */
    public fun hostDied() {
        connections.value.values.forEach { it.radio?.retire() }
        connections.value = emptyMap()
        saved.filterNot { it.ref.family == DeviceFamily.VacuGlide }.forEach { idle(it.ref.id) }
    }

    private fun idle(id: DeviceId) {
        outputs.update { old ->
            old[id]?.let { old + (id to it.copy(speed = Percent(0), strengthA = 0, strengthB = 0)) }
                ?: old
        }
    }

    override suspend fun stopSavedCloud(device: DeviceRef, fence: OutputFence): StopEvidence {
        if (
            device.connection !in setOf(ConnectionKind.AutoblowCloud, ConnectionKind.HandyCloud) ||
                saved.none { it.ref.id == device.id && it.ref.connection == device.connection }
        )
            return StopEvidence.Unconfirmed(UnconfirmedCause.LinkLost)
        return stopDevice(device, fence)
    }

    private fun stopDevice(device: DeviceRef, fence: OutputFence): StopEvidence {
        if (!fence.isCurrent() || device.id in unavailable.value)
            return StopEvidence.Unconfirmed(UnconfirmedCause.LinkLost)
        rememberStop(device.id)
        return when (device.family) {
            DeviceFamily.Coyote -> StopEvidence.Unconfirmed(UnconfirmedCause.NoReply)
            DeviceFamily.Vibrator -> StopEvidence.Unconfirmed(UnconfirmedCause.BridgeOnly)
            DeviceFamily.Handy,
            DeviceFamily.AutoblowUltra,
            DeviceFamily.VacuGlide -> StopEvidence.Acknowledged
        }
    }

    private fun rememberStop(id: DeviceId) {
        outputs.update { old ->
            val prior = old.getValue(id)
            old +
                (id to
                    prior.copy(
                        speed = Percent(0),
                        strengthA = 0,
                        strengthB = 0,
                        stops = prior.stops + 1,
                    ))
        }
    }

    private inner class Owner(
        private val selected: SavedDevice,
        val session: HandyBluetoothSession?,
        val radio: SimulatedHandyLink?,
    ) : DeviceTransport {
        override val device = selected.ref
        override val stopTimeout = session?.stopTimeout ?: 900.milliseconds

        override suspend fun write(batch: MotionBatch, fence: OutputFence): WriteResult {
            if (!fence.isCurrent()) return WriteResult.Stale
            if (connections.value[device.id] !== this || device.id in unavailable.value)
                return WriteResult.Uncertain
            val accepted = session?.write(batch, fence)
            if (accepted != null && accepted != WriteResult.Accepted) return accepted
            val speed =
                when (val settings = selected.settings) {
                    is DeviceSettings.Coyote ->
                        0 // No reported baseline or manual strength was supplied.
                    is DeviceSettings.Vibrator -> minOf(settings.maxLevel.value, batch.pace.value)
                    is DeviceSettings.VacuGlide ->
                        batch.pace.value.coerceIn(0, settings.tempoRange.last)
                    is DeviceSettings.Handy,
                    DeviceSettings.AutoblowUltra -> batch.pace.value
                }
            while (true) {
                val old = outputs.value
                val prior = old.getValue(device.id)
                if (!fence.isCurrent()) return WriteResult.Stale
                if (
                    outputs.compareAndSet(
                        old,
                        old +
                            (device.id to
                                prior.copy(speed = Percent(speed), batches = prior.batches + 1)),
                    )
                )
                    return WriteResult.Accepted
            }
        }

        override suspend fun stop(fence: OutputFence): StopEvidence =
            session?.stop(fence) ?: stopDevice(device, fence)
    }
}

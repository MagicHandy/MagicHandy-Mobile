package com.magichandy.mobile.platform

import android.content.Context
import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.platform.bluetooth.AndroidBluetooth
import com.magichandy.mobile.platform.storage.RoomStores
import com.magichandy.mobile.transport.ble.*
import com.magichandy.mobile.transport.handy.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient

internal class PlatformDevices(
    private val context: Context,
    private val stores: RoomStores,
    private val vault: SecretsVault,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val network: NetworkMonitor,
) : DeviceDirectory {
    private val events = MutableSharedFlow<DeviceId>()
    override val losses = events.asSharedFlow()
    private val radio =
        AndroidBluetooth(context) { address ->
            scope.launch {
                saved.filter { it.address == address }.forEach { events.emit(it.ref.id) }
            }
        }
    override val bluetoothOn = radio.enabled.asStateFlow()
    override val configuration: DeviceConfiguration =
        object : DeviceConfiguration, DeviceRecords by stores.devices {
            override suspend fun intifaceVibrators(address: String) =
                com.magichandy.mobile.transport.intiface.IntifaceVibrator.choices(http, address)

            override val scanAllowed
                get() = radio.permissionGranted()

            override fun scan(family: DeviceFamily): Flow<ScanResult> {
                val profile =
                    if (family == DeviceFamily.Coyote) GattProfile.Coyote else GattProfile.Handy
                return radio.scan(profile).map {
                    ScanResult(it.id, it.name, it.signalDbm, ScanResultStatus.Found)
                }
            }

            override fun address(result: ScanResultId) = radio.address(result)
        }

    fun refresh() {
        radio.refresh()
    }

    override val saved
        get() = stores.devices.rows.value

    private val sessions = ConcurrentHashMap<DeviceId, suspend () -> Unit>()

    override suspend fun setHandyReversed(device: DeviceId, isReversed: Boolean): Boolean {
        val row = saved.firstOrNull { it.ref.id == device } ?: return false
        val settings = row.settings as? DeviceSettings.Handy ?: return false
        stores.devices.save(row.copy(settings = settings.copy(isReversed = isReversed)))
        return true
    }

    private fun reversed(id: DeviceId) =
        (saved.firstOrNull { it.ref.id == id }?.settings as? DeviceSettings.Handy)?.isReversed ==
            true

    private suspend fun cloud(row: SavedDevice): DeviceTransport? {
        val key = row.credential?.let { vault.get(it) } ?: return null
        return if (row.ref.connection == ConnectionKind.HandyCloud)
            createHandyCloud(
                row.ref,
                key,
                Secret(
                    context.getString(com.magichandy.mobile.R.string.platform_handy_application_id)
                ),
                http,
                AndroidClock,
            ) {
                reversed(row.ref.id)
            }
        else null
    }

    override suspend fun connect(device: DeviceId, ownerScope: CoroutineScope): DeviceTransport? {
        stores.awaitReady()
        val row = saved.firstOrNull { it.ref.id == device } ?: return null
        return when (row.ref.connection) {
            ConnectionKind.Bluetooth -> {
                if (row.ref.family == DeviceFamily.Coyote) {
                    val session =
                        com.magichandy.mobile.transport.coyote.CoyoteSession.connect(
                            row.ref,
                            ScanResultId(row.address ?: return null),
                            radio,
                            ownerScope,
                            {
                                saved.first { it.ref.id == device }.settings
                                    as DeviceSettings.Coyote
                            },
                            { scope.launch { events.emit(device) } },
                        )
                    sessions[device] = { session.close() }
                    return session
                }
                if (row.ref.family != DeviceFamily.Handy) return null
                val session =
                    HandyBluetoothSession.connect(
                        row.ref,
                        ScanResultId(row.address ?: return null),
                        radio,
                        ownerScope,
                        AndroidClock,
                    ) {
                        reversed(device)
                    }
                sessions[device] = {
                    session.disconnect()
                }
                session
            }
            ConnectionKind.HandyCloud ->
                cloud(row)?.takeIf { it.confirmIdleForConnection() == StopEvidence.Acknowledged }
            ConnectionKind.AutoblowCloud -> {
                val key = row.credential?.let { vault.get(it) } ?: return null
                val core = (context.applicationContext as CoreOwner).core
                val session =
                    com.magichandy.mobile.transport.autoblow.AutoblowSession.connect(
                        row,
                        key,
                        http,
                        AndroidClock,
                        ownerScope,
                        { saved.first { it.ref.id == device }.settings },
                        { scope.launch { events.emit(device) } },
                    ) { button ->
                        when (button) {
                            com.magichandy.mobile.transport.autoblow.AutoblowButton.Stop ->
                                core.emergencyStop(StopSource.DeviceButton)
                            com.magichandy.mobile.transport.autoblow.AutoblowButton.Faster ->
                                if (row.ref.family == DeviceFamily.VacuGlide)
                                    core.stepTempoRange(device, StepDirection.Up)
                                else
                                    core.dispatch(
                                        UserIntent.StepLimit(LimitKind.TopSpeed, StepDirection.Up)
                                    )
                            com.magichandy.mobile.transport.autoblow.AutoblowButton.Slower ->
                                if (row.ref.family == DeviceFamily.VacuGlide)
                                    core.stepTempoRange(device, StepDirection.Down)
                                else
                                    core.dispatch(
                                        UserIntent.StepLimit(LimitKind.TopSpeed, StepDirection.Down)
                                    )
                        }
                    }
                sessions[device] = { session.close() }
                session
            }
            ConnectionKind.IntifaceOnNetwork,
            ConnectionKind.IntifaceOnPhone -> {
                val session =
                    com.magichandy.mobile.transport.intiface.IntifaceVibrator.connect(
                        row.ref,
                        row.address ?: return null,
                        row.deviceIndex,
                        http,
                        ownerScope,
                        {
                            (saved.first { it.ref.id == device }.settings
                                    as DeviceSettings.Vibrator)
                                .maxLevel
                        },
                        { scope.launch { events.emit(device) } },
                    )
                sessions[device] = { session.close() }
                session
            }
        }
    }

    override suspend fun disconnect(device: DeviceId) {
        sessions.remove(device)?.invoke()
    }

    override fun reachableAgain(device: DeviceRef): Flow<Unit> = flow {
        if (device.family != DeviceFamily.VacuGlide) return@flow
        // The vendor has no push event once its connection is gone. Only an outstanding
        // recovery subscribes. Stop and explicit local reconciliation cancel this collection.
        var delaySeconds = 5L
        while (currentCoroutineContext().isActive) {
            delay(delaySeconds * 1000)
            network.observe().first { it.isOnline }
            stores.awaitReady()
            val row =
                saved.firstOrNull { it.ref.id == device.id && it.ref.family == device.family }
                    ?: return@flow
            val key =
                try {
                    row.credential?.let { vault.get(it) }
                } catch (_: java.io.IOException) {
                    DiagnosticReporter.record(DiagnosticKind.StoreUnavailable)
                    null
                } ?: return@flow
            if (com.magichandy.mobile.transport.autoblow.AutoblowSession.reachable(key, http))
                emit(Unit)
            delaySeconds = (delaySeconds * 2).coerceAtMost(30)
        }
    }
        .flowOn(Dispatchers.IO)

    override suspend fun stopSavedCloud(device: DeviceRef, fence: OutputFence): StopEvidence {
        stores.awaitReady()
        val row = saved.firstOrNull {
            it.ref.id == device.id && it.ref.connection == device.connection
        }
        if (row?.ref?.connection == ConnectionKind.AutoblowCloud) {
            val key =
                row.credential?.let { vault.get(it) }
                    ?: return StopEvidence.Unconfirmed(UnconfirmedCause.LinkLost)
            return com.magichandy.mobile.transport.autoblow.AutoblowSession.recover(
                row,
                key,
                http,
                AndroidClock,
                scope,
                fence,
            )
        }
        return row?.let { cloud(it) }?.stop(fence)
            ?: StopEvidence.Unconfirmed(UnconfirmedCause.LinkLost)
    }
}

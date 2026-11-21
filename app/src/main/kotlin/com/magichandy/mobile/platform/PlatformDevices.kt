package com.magichandy.mobile.platform

import android.content.Context
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceConnectionFailure
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.LimitKind
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.ScanResult
import com.magichandy.mobile.core.api.ScanResultId
import com.magichandy.mobile.core.api.ScanResultStatus
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.StepDirection
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DeviceConfiguration
import com.magichandy.mobile.core.ports.DeviceConnectionException
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceLinkLoss
import com.magichandy.mobile.core.ports.DeviceRecords
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.NetworkMonitor
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.SavedDevice
import com.magichandy.mobile.core.ports.SecretsVault
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.confirmIdleForConnection
import com.magichandy.mobile.platform.bluetooth.AndroidBluetooth
import com.magichandy.mobile.platform.storage.RoomStores
import com.magichandy.mobile.transport.autoblow.AutoblowButton
import com.magichandy.mobile.transport.autoblow.AutoblowSession
import com.magichandy.mobile.transport.ble.GattProfile
import com.magichandy.mobile.transport.coyote.CoyoteSession
import com.magichandy.mobile.transport.handy.HandyBluetoothSession
import com.magichandy.mobile.transport.handy.createHandyCloud
import com.magichandy.mobile.transport.intiface.IntifaceHub
import com.magichandy.mobile.transport.intiface.IntifaceLinear
import com.magichandy.mobile.transport.intiface.IntifaceVibrator
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

internal class PlatformDevices(
    private val context: Context,
    private val stores: RoomStores,
    private val vault: SecretsVault,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val network: NetworkMonitor,
) : DeviceDirectory {
    private val bridges = IntifaceHub(http, scope)

    override fun stopOpenBridges(epoch: Epoch) = bridges.emergencyStop(epoch)

    private val events = MutableSharedFlow<DeviceLinkLoss>()
    override val connectionLosses = events.asSharedFlow()
    override val losses = connectionLosses.map { it.device }
    private val radio = AndroidBluetooth(context)
    override val bluetoothOn = radio.enabled.asStateFlow()
    override val configuration: DeviceConfiguration =
        object : DeviceConfiguration, DeviceRecords by stores.devices {
            override suspend fun bridgeTargets(address: String) = bridges.targets(address)

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
        var supported = false
        stores.devices.update(device) { row ->
            val settings =
                when (val current = row.settings) {
                    is DeviceSettings.Handy -> current.copy(isReversed = isReversed)
                    is DeviceSettings.LinearActuator -> current.copy(isReversed = isReversed)
                    else -> return@update row
                }
            supported = true
            row.copy(settings = settings)
        }
        return supported
    }

    private fun reversed(id: DeviceId) =
        when (val s = saved.firstOrNull { it.ref.id == id }?.settings) {
            is DeviceSettings.Handy -> s.isReversed
            is DeviceSettings.LinearActuator -> s.isReversed
            else -> false
        }

    private suspend fun deviceCredential(row: SavedDevice): Secret =
        try {
            row.credential?.let { vault.get(it) }
        } catch (_: IOException) {
            DiagnosticReporter.record(DiagnosticKind.StoreUnavailable)
            null
        }
            ?: throw DeviceConnectionException(
                DeviceConnectionFailure(DeviceConnectionFailure.Reason.CredentialUnavailable)
            )

    private suspend fun cloud(row: SavedDevice): DeviceTransport =
        createHandyCloud(
            row.ref,
            deviceCredential(row),
            Secret(context.getString(R.string.platform_handy_application_id)),
            http,
            AndroidClock,
        ) {
            reversed(row.ref.id)
        }

    override suspend fun connect(device: DeviceId, ownerScope: CoroutineScope): DeviceTransport? {
        stores.awaitReady()
        val row = saved.firstOrNull { it.ref.id == device } ?: return null
        val event = DeviceLinkLoss(device, requireNotNull(ownerScope.coroutineContext[Job]))
        val reported = AtomicBoolean()
        val lost: () -> Unit = {
            if (reported.compareAndSet(false, true)) ownerScope.launch { events.emit(event) }
        }
        return when (row.ref.connection) {
            ConnectionKind.Bluetooth -> {
                val connector = radio.connector(lost)
                if (row.ref.family == DeviceFamily.Coyote) {
                    val session =
                        CoyoteSession.connect(
                            row.ref,
                            ScanResultId(row.address ?: return null),
                            connector,
                            ownerScope,
                            {
                                (saved.firstOrNull { it.ref.id == device }?.settings
                                    as? DeviceSettings.Coyote) ?: DeviceSettings.Coyote(0, 0)
                            },
                            lost,
                        )
                    sessions[device] = { session.close() }
                    return session
                }
                if (row.ref.family != DeviceFamily.Handy) return null
                val session =
                    HandyBluetoothSession.connect(
                        row.ref,
                        ScanResultId(row.address ?: return null),
                        connector,
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
            ConnectionKind.HandyCloud -> {
                val owner = cloud(row)
                owner.takeIf { it.confirmIdleForConnection() == StopEvidence.Acknowledged }
            }
            ConnectionKind.AutoblowCloud -> {
                val key = deviceCredential(row)
                val core = (context.applicationContext as CoreOwner).core
                val session =
                    AutoblowSession.connect(
                        row,
                        key,
                        http,
                        AndroidClock,
                        ownerScope,
                        {
                            saved.firstOrNull { it.ref.id == device }?.settings
                                ?: if (row.ref.family == DeviceFamily.VacuGlide)
                                    DeviceSettings.VacuGlide(0..0, false, null)
                                else DeviceSettings.AutoblowUltra
                        },
                        lost,
                        topSpeed = {
                            if (saved.any { it.ref.id == device })
                                stores.settings.state.value.values.limits.topSpeed.value
                            else 0
                        },
                    ) { button ->
                        when (button) {
                            AutoblowButton.Stop -> core.emergencyStop(StopSource.DeviceButton)
                            AutoblowButton.Faster ->
                                if (row.ref.family == DeviceFamily.VacuGlide)
                                    core.stepTempoRange(device, StepDirection.Up)
                                else
                                    core.dispatch(
                                        UserIntent.StepLimit(LimitKind.TopSpeed, StepDirection.Up)
                                    )
                            AutoblowButton.Slower ->
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
                if (row.ref.family != DeviceFamily.Vibrator) {
                    val linear =
                        IntifaceLinear.connect(
                            bridges,
                            row,
                            ownerScope,
                            AndroidClock,
                            { stores.settings.state.value.values.limits },
                            { reversed(device) },
                            lost,
                        )
                    sessions[device] = { linear.close() }
                    return linear
                }
                val session =
                    IntifaceVibrator.connect(
                        bridges,
                        row,
                        {
                            (saved.firstOrNull { it.ref.id == device }?.settings
                                    as? DeviceSettings.Vibrator)
                                ?.maxLevel ?: Percent(0)
                        },
                        lost,
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
                } catch (_: IOException) {
                    DiagnosticReporter.record(DiagnosticKind.StoreUnavailable)
                    null
                } ?: return@flow
            if (AutoblowSession.reachable(key, http)) emit(Unit)
            delaySeconds = (delaySeconds * 2).coerceAtMost(30)
        }
    }
        .flowOn(Dispatchers.IO)

    override suspend fun stopSavedCloud(device: DeviceRef, fence: OutputFence): StopEvidence {
        stores.awaitReady()
        val row = saved.firstOrNull {
            it.ref.id == device.id && it.ref.connection == device.connection
        }
        return try {
            when (row?.ref?.connection) {
                ConnectionKind.AutoblowCloud ->
                    AutoblowSession.recover(
                        row,
                        deviceCredential(row),
                        http,
                        AndroidClock,
                        scope,
                        fence,
                    )
                ConnectionKind.HandyCloud -> cloud(row).stop(fence)
                else -> StopEvidence.Unconfirmed(UnconfirmedCause.LinkLost)
            }
        } catch (_: DeviceConnectionException) {
            StopEvidence.Unconfirmed(UnconfirmedCause.LinkLost)
        }
    }
}

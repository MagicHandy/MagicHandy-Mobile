package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.ports.*
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class DeviceSetup(
    private val d: CoreDependencies,
    private val ui: MutableStateFlow<AppState>,
    private val devices: Devices,
    private val engine: MotionEngine,
) {
    private val config
        get() = d.devices.configuration

    private var discovery: Job? = null
    private var selection: Job? = null
    private var generation = 0L

    fun refresh(rows: List<SavedDevice>) {
        ui.update { s ->
            val previous = s.devices.devices.associateBy { it.id }
            s.copy(
                devices =
                    s.devices.copy(
                        devices =
                            rows.map { row ->
                                val r = row.ref
                                previous[r.id]?.copy(name = r.name, settings = row.settings)
                                    ?: DeviceSummary(
                                        r.id,
                                        r.name,
                                        r.family,
                                        r.connection,
                                        DeviceStatus.NotConnected,
                                        RunMembership.NotInRun,
                                        null,
                                        null,
                                        null,
                                        row.settings,
                                        r.afterLoss,
                                    )
                            }
                    )
            )
        }
    }

    fun stopScan() {
        generation++
        discovery?.cancel()
        selection?.cancel()
        scan(ScanState.Idle)
    }

    fun setCoyoteLimits(device: DeviceId, maximumA: Int, maximumB: Int) {
        if (maximumA !in 0..200 || maximumB !in 0..200) return
        d.scope.launch(d.dispatchers.session) {
            if (device !in engine.state.value.owners && ui.value.devices.hasAcknowledgedCoyoteNotes)
                edit(device) { row ->
                    if (row.settings is DeviceSettings.Coyote)
                        row.copy(
                            settings =
                                DeviceSettings.Coyote(
                                    maximumA,
                                    maximumB,
                                )
                        )
                    else row
                }
        }
    }

    fun stepCoyote(
        device: DeviceId,
        channel: CoyoteChannel,
        increase: Boolean,
    ) {
        val s = engine.state.value
        if (
            device in s.members &&
                s.pausedPosition == null &&
                ui.value.devices.hasAcknowledgedCoyoteNotes
        )
            (s.owners[device] as? CoyoteControls)?.step(
                channel,
                increase,
                s.epoch,
                s.streamGeneration,
            )
    }

    fun stepTempoRange(device: DeviceId, direction: StepDirection) {
        d.scope.launch(d.dispatchers.session) {
            edit(device) { row ->
                val cfg = row.settings as? DeviceSettings.VacuGlide ?: return@edit row
                val high =
                    (cfg.tempoRange.last + if (direction == StepDirection.Up) 10 else -10).coerceIn(
                        cfg.tempoRange.first,
                        100,
                    )
                row.copy(settings = cfg.copy(tempoRange = cfg.tempoRange.first..high))
            }
        }
    }

    private fun scan(s: ScanState) {
        ui.update { it.copy(devices = it.devices.copy(scan = s)) }
    }

    fun startScan(family: DeviceFamily) {
        stopScan()
        val cfg = config ?: return
        if (
            !ui.value.firstRun.hasAcknowledgedAdultsOnly ||
                family !in setOf(DeviceFamily.Handy, DeviceFamily.Coyote) ||
                family == DeviceFamily.Coyote && !ui.value.devices.hasAcknowledgedCoyoteNotes
        )
            return
        if (!cfg.scanAllowed) {
            scan(ScanState.Failed(family, ScanFailure.PermissionDenied))
            return
        }
        if (!d.devices.bluetoothOn.value) {
            scan(ScanState.Failed(family, ScanFailure.BluetoothOff))
            return
        }
        val token = generation
        scan(ScanState.Scanning(family, emptyList()))
        discovery =
            d.scope.launch(d.dispatchers.session) {
                try {
                    withTimeoutOrNull(12.seconds) {
                        cfg.scan(family).collect { result ->
                            val state =
                                ui.value.devices.scan as? ScanState.Scanning ?: return@collect
                            if (token != generation) return@collect
                            val index = state.results.indexOfFirst { it.id == result.id }
                            if (index < 0 && state.results.size >= 64) return@collect
                            scan(
                                state.copy(
                                    results =
                                        if (index < 0) state.results + result
                                        else
                                            state.results.toMutableList().apply {
                                                this[index] =
                                                    this[index].copy(signalDbm = result.signalDbm)
                                            }
                                )
                            )
                        }
                    }
                    if (
                        token == generation &&
                            (ui.value.devices.scan as? ScanState.Scanning)?.results?.isEmpty() ==
                                true
                    )
                        scan(ScanState.Failed(family, ScanFailure.NothingFound))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SecurityException) {
                    if (token == generation)
                        scan(ScanState.Failed(family, ScanFailure.PermissionDenied))
                } catch (_: java.io.IOException) {
                    if (token == generation)
                        scan(ScanState.Failed(family, ScanFailure.NothingFound))
                }
            }
    }

    suspend fun select(intent: UserIntent.ConnectScanResult) {
        val cfg = config ?: return
        val state = ui.value.devices.scan as? ScanState.Scanning ?: return
        if (
            selection?.isActive == true ||
                state.results.none { it.id == intent.result && it.status == ScanResultStatus.Found }
        )
            return
        val address = cfg.address(intent.result) ?: return
        val saved =
            cfg.rows.value.firstOrNull { it.address == address && it.ref.family == state.family }
                ?: newRow(state.family, ConnectionKind.Bluetooth, intent.name, address = address)
        cfg.save(saved)
        refresh(cfg.rows.value)
        val token = generation
        fun status(s: ScanResultStatus) {
            val scan = ui.value.devices.scan as? ScanState.Scanning ?: return
            if (token == generation)
                scan(
                    scan.copy(
                        results =
                            scan.results.map {
                                if (it.id == intent.result) it.copy(status = s) else it
                            }
                    )
                )
        }
        status(ScanResultStatus.Verifying)
        devices.connect(saved.ref.id)
        selection =
            d.scope.launch(d.dispatchers.session) {
                val row =
                    withTimeoutOrNull(30.seconds) {
                        ui.map { s -> s.devices.devices.firstOrNull { it.id == saved.ref.id } }
                            .first { it?.status != DeviceStatus.Connecting }
                    }
                status(
                    if (row?.status == DeviceStatus.Connected) ScanResultStatus.Verified
                    else ScanResultStatus.Failed
                )
            }
    }

    suspend fun cloud(family: DeviceFamily, name: String, key: Secret): FormResult {
        val cfg = config ?: return reject(FormReason.Unsupported)
        if (!ui.value.firstRun.hasAcknowledgedAdultsOnly) return reject(FormReason.ConsentRequired)
        if (name.trim().length > 64 || name.any(Char::isISOControl))
            return reject(FormReason.InvalidName)
        if (
            family !in setOf(DeviceFamily.Handy, DeviceFamily.VacuGlide, DeviceFamily.AutoblowUltra)
        )
            return reject(FormReason.Unsupported)
        val valid =
            when (family) {
                DeviceFamily.Handy -> key.reveal().matches(Regex("[A-Za-z0-9]{5,64}"))
                DeviceFamily.AutoblowUltra,
                DeviceFamily.VacuGlide ->
                    key.reveal().length in 1..256 && key.reveal().all { it.code in 33..126 }
                else -> false
            }
        if (!valid) return reject(FormReason.InvalidKey)
        for (saved in cfg.rows.value) {
            if (
                saved.credential != null &&
                    (family == DeviceFamily.Handy) == (saved.ref.family == DeviceFamily.Handy) &&
                    d.vault.get(saved.credential) == key
            )
                return reject(FormReason.Duplicate)
        }
        val kind =
            if (family == DeviceFamily.Handy) ConnectionKind.HandyCloud
            else ConnectionKind.AutoblowCloud
        val row = newRow(family, kind, name)
        val handle = SecretHandle("device-" + row.ref.id.value)
        d.vault.put(handle, key)
        try {
            cfg.save(row.copy(credential = handle))
        } finally {
            if (cfg.rows.value.none { it.credential == handle }) d.vault.remove(handle)
        }
        refresh(cfg.rows.value)
        return FormResult.Accepted
    }

    val bridges = BridgeSetup(d, ui, ::refresh)

    suspend fun edit(id: DeviceId, transform: (SavedDevice) -> SavedDevice) {
        val cfg = config ?: return
        cfg.rows.value
            .firstOrNull { it.ref.id == id }
            ?.let {
                cfg.save(transform(it))
                refresh(cfg.rows.value)
            }
    }

    suspend fun rename(intent: UserIntent.Rename): FormResult {
        val cfg = config ?: return reject(FormReason.Unsupported)
        val name = intent.name.trim()
        if (name.isEmpty() || name.length > 64 || name.any(Char::isISOControl))
            return reject(FormReason.InvalidName)
        val row =
            cfg.rows.value.firstOrNull { it.ref.id == intent.device }
                ?: return reject(FormReason.NotFound)
        if (cfg.rows.value.any { it.ref.id != intent.device && it.ref.name.equals(name, true) })
            return reject(FormReason.Duplicate)
        cfg.save(row.copy(ref = row.ref.copy(name = name)))
        refresh(cfg.rows.value)
        return FormResult.Accepted
    }

    suspend fun remove(id: DeviceId): FormResult {
        val cfg = config ?: return reject(FormReason.Unsupported)
        val row = cfg.rows.value.find { it.ref.id == id } ?: return reject(FormReason.NotFound)
        if (id in engine.state.value.members) return reject(FormReason.RunInProgress)
        if (ui.value.devices.devices.any { it.id == id && it.status == DeviceStatus.Connecting })
            return reject(FormReason.Busy)
        if (id in engine.state.value.owners) {
            devices.disconnect(id)
        }
        val state = engine.state.value
        if (
            state.pending.containsKey(id) ||
                state.journal?.devices.orEmpty().any { it.id == id && id !in state.resolved } ||
                state.receipt?.outcomes.orEmpty().any {
                    it.device == id && it.outcome != StopOutcome.Confirmed
                }
        )
            return reject(FormReason.ReconcileDevice)
        removeWithCredential(d.vault, row.credential, { cfg.rows.value.any { it.ref.id == id } }) {
            cfg.remove(id)
        }
        refresh(cfg.rows.value)
        return FormResult.Accepted
    }

    private fun newRow(
        family: DeviceFamily,
        kind: ConnectionKind,
        name: String,
        address: String? = null,
    ) =
        SavedDevice(
            DeviceRef(
                DeviceId(UUID.randomUUID().toString()),
                name.trim().take(64).ifEmpty { family.name },
                family,
                kind,
            ),
            defaultDeviceSettings(family),
            address,
        )
}

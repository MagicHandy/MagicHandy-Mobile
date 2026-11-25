package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.CoyoteChannel
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceLimit
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.api.ScanFailure
import com.magichandy.mobile.core.api.ScanResultStatus
import com.magichandy.mobile.core.api.ScanState
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.StepDirection
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.ports.CoyoteControls
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.SavedDevice
import com.magichandy.mobile.core.ports.SecretHandle
import com.magichandy.mobile.core.ports.defaultDeviceSettings
import com.magichandy.mobile.core.ports.matches
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class DeviceSetup(
    private val d: CoreDependencies,
    private val ui: MutableStateFlow<AppState>,
    private val devices: Devices,
    private val engine: MotionEngine,
    private val safety: RunSafety,
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
                                previous[r.id]?.copy(
                                    name = r.name,
                                    connection = r.connection,
                                    afterLoss = r.afterLoss,
                                    settings = row.settings,
                                )
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

    suspend fun setModel(intent: UserIntent.SetHandyModel) {
        if (intent.device in engine.state.value.members) return
        edit(intent.device) { row ->
            val handy = row.settings as? DeviceSettings.Handy ?: return@edit row
            row.copy(settings = handy.copy(model = intent.model.storedName))
        }
    }

    suspend fun stepLimit(intent: UserIntent.StepDeviceLimit) {
        val step = intent.steps * if (intent.direction == StepDirection.Up) 5 else -5
        edit(intent.device) { row ->
            val settings =
                when (val saved = row.settings) {
                    is DeviceSettings.Vibrator ->
                        if (intent.limit == DeviceLimit.VibrationMaximum)
                            saved.copy(
                                maxLevel = Percent((saved.maxLevel.value + step).coerceIn(0, 100))
                            )
                        else saved
                    is DeviceSettings.VacuGlide -> {
                        val (low, high) = saved.tempoRange.let { it.first to it.last }
                        when (intent.limit) {
                            DeviceLimit.TempoMinimum ->
                                saved.copy(tempoRange = (low + step).coerceIn(5, high)..high)
                            DeviceLimit.TempoMaximum ->
                                saved.copy(tempoRange = low..(high + step).coerceIn(low, 100))
                            DeviceLimit.VibrationMaximum -> saved
                        }
                    }
                    else -> saved
                }
            row.copy(settings = settings)
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
        // A result that couldn't be verified can be tried again from the same list.
        if (
            selection?.isActive == true ||
                state.results.none {
                    it.id == intent.result &&
                        (it.status == ScanResultStatus.Found ||
                            it.status == ScanResultStatus.Failed)
                }
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
        // Scanning competes with connecting for the radio. The chosen list stays on screen.
        discovery?.cancel()
        devices.connect(saved.ref.id)
        selection =
            d.scope.launch(d.dispatchers.session) {
                // Covers every Bluetooth connection attempt, so a slow success can't show as
                // failed.
                val row =
                    withTimeoutOrNull(60.seconds) {
                        ui.map { s -> s.devices.devices.firstOrNull { it.id == saved.ref.id } }
                            .first { it?.status != DeviceStatus.Connecting }
                    }
                status(
                    if (row?.status == DeviceStatus.Connected) ScanResultStatus.Verified
                    else ScanResultStatus.Failed
                )
            }
    }

    suspend fun cloud(
        family: DeviceFamily,
        name: String,
        key: Secret,
        connectAfterSave: Boolean,
    ): FormResult {
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
                    d.vault.matches(saved.credential, key)
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
        if (connectAfterSave) devices.connect(row.ref.id)
        return FormResult.Accepted
    }

    val bridges = BridgeSetup(d, ui, ::refresh, safety::canForget)

    suspend fun edit(id: DeviceId, transform: (SavedDevice) -> SavedDevice) {
        val cfg = config ?: return
        cfg.update(id, transform)
        refresh(cfg.rows.value)
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
        cfg.update(row.ref.id) { it.copy(ref = it.ref.copy(name = name)) }
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
            !safety.canForget(id) ||
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

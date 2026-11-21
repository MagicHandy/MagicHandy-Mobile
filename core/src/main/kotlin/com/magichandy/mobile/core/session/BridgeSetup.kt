package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.BridgeDiscovery
import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.IntifaceChoice
import com.magichandy.mobile.core.api.IntifaceLinearChoice
import com.magichandy.mobile.core.ports.BridgeTarget
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.SavedDevice
import com.magichandy.mobile.core.ports.defaultDeviceSettings
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Resolves the last visible choice to a stable identity before saving a named bridge device. */
internal class BridgeSetup(
    private val d: CoreDependencies,
    private val ui: MutableStateFlow<AppState>,
    private val refresh: (List<SavedDevice>) -> Unit,
    private val canRebind: suspend (DeviceId) -> Boolean,
) {
    private data class Listing(
        val address: String,
        val request: Long,
        val targets: List<BridgeTarget> = emptyList(),
        val conflicts: Set<Pair<Int, Int>> = emptySet(),
    )

    private val listings = mutableMapOf<Boolean, Listing>()
    private var next = 0L

    private val connections = kotlinx.coroutines.sync.Semaphore(2)

    private suspend fun discover(address: String, linear: Boolean): BridgeDiscovery<BridgeTarget> {
        if (!connections.tryAcquire()) return BridgeDiscovery.Failed(FormReason.Busy)
        return try {
            lookup(address, linear)
        } finally {
            connections.release()
        }
    }

    private suspend fun lookup(address: String, linear: Boolean): BridgeDiscovery<BridgeTarget> {
        val cfg = d.devices.configuration ?: return BridgeDiscovery.Failed(FormReason.Unsupported)
        val uri = bridgeAddress(address) ?: return BridgeDiscovery.Failed(FormReason.InvalidAddress)
        if (!ui.value.firstRun.hasAcknowledgedAdultsOnly)
            return BridgeDiscovery.Failed(FormReason.ConsentRequired)
        val key = uri.toASCIIString()
        val request =
            synchronized(listings) {
                val last = listings[linear]?.takeIf { it.address == key } ?: Listing(key, 0)
                (++next).also { listings[linear] = last.copy(request = it) }
            }
        val list =
            try {
                withTimeoutOrNull(8000) { withContext(d.dispatchers.io) { cfg.bridgeTargets(key) } }
                    ?: return BridgeDiscovery.Failed(FormReason.Unreachable)
            } catch (_: java.io.IOException) {
                DiagnosticReporter.record(DiagnosticKind.DeviceConnectionFailed)
                return BridgeDiscovery.Failed(FormReason.Unreachable)
            }
        val valid = list.filter { (it.family != DeviceFamily.Vibrator) == linear }
        return synchronized(listings) {
            val last =
                listings[linear] ?: return@synchronized BridgeDiscovery.Failed(FormReason.Busy)
            if (last.request != request) return@synchronized BridgeDiscovery.Failed(FormReason.Busy)
            val previous = last.targets.associateBy { it.deviceIndex to it.actuatorIndex }
            val conflicts =
                valid
                    .mapNotNull { candidate ->
                        val slot = candidate.deviceIndex to candidate.actuatorIndex
                        slot.takeIf {
                            it in last.conflicts ||
                                previous[it]?.let { old -> old.identity != candidate.identity } ==
                                    true
                        }
                    }
                    .toSet()
            listings[linear] = last.copy(targets = valid, conflicts = conflicts)
            BridgeDiscovery.Found(valid)
        }
    }

    private fun <T> BridgeDiscovery<BridgeTarget>.choicesAs(
        convert: (BridgeTarget) -> T
    ): BridgeDiscovery<T> =
        when (this) {
            is BridgeDiscovery.Found -> BridgeDiscovery.Found(choices.map(convert))
            is BridgeDiscovery.Failed -> this
        }

    suspend fun vibratorsAt(address: String) =
        discover(address, false).choicesAs { IntifaceChoice(it.deviceIndex, it.name, it.identity) }

    suspend fun linearsAt(address: String) =
        discover(address, true).choicesAs {
            IntifaceLinearChoice(
                it.deviceIndex,
                it.actuatorIndex,
                it.name,
                it.descriptor,
                it.identity,
            )
        }

    private fun bridgeAddress(address: String): URI? {
        val uri =
            try {
                URI(if ("://" in address) address.trim() else "ws://" + address.trim())
            } catch (_: java.net.URISyntaxException) {
                return null
            }
        val scheme = uri.scheme?.lowercase()
        if (
            scheme !in setOf("ws", "wss") ||
                uri.host == null ||
                uri.userInfo != null ||
                uri.query != null ||
                uri.fragment != null
        )
            return null
        if (uri.port == 0 || uri.port > 65535) return null
        val port =
            uri.port.takeUnless {
                it < 0 || it == 80 && scheme == "ws" || it == 443 && scheme == "wss"
            }
        return URI(
                scheme +
                    "://" +
                    uri.host.lowercase() +
                    (port?.let { ":" + it }.orEmpty()) +
                    uri.rawPath.ifEmpty { "/" }
            )
            .normalize()
    }

    suspend fun intiface(
        address: String,
        index: Int?,
        axis: Int? = null,
        linear: Boolean = false,
        selection: String? = null,
        replaceDevice: DeviceId? = null,
    ): FormResult {
        val cfg = d.devices.configuration ?: return reject(FormReason.Unsupported)
        if (!ui.value.firstRun.hasAcknowledgedAdultsOnly) return reject(FormReason.ConsentRequired)
        val uri = bridgeAddress(address) ?: return reject(FormReason.InvalidAddress)
        val key = uri.toASCIIString()
        val listing = synchronized(listings) { listings[linear]?.takeIf { it.address == key } }
        val prior = listing?.targets
        val selected = prior?.singleOrNull {
            (index == null || it.deviceIndex == index) && (axis == null || it.actuatorIndex == axis)
        }
        if (
            selection == null &&
                selected != null &&
                (selected.deviceIndex to selected.actuatorIndex) in listing.conflicts
        )
            return reject(FormReason.ChooseDevice)
        if (
            selection == null &&
                (prior != null || index != null || axis != null) &&
                selected == null
        )
            return reject(FormReason.ChooseDevice)
        val available =
            try {
                withTimeoutOrNull(8000) { withContext(d.dispatchers.io) { cfg.bridgeTargets(key) } }
                    ?: return reject(FormReason.Unreachable)
            } catch (_: java.io.IOException) {
                return reject(FormReason.Unreachable)
            }
        val eligible = available.filter { (it.family != DeviceFamily.Vibrator) == linear }
        val identity = selection ?: selected?.identity
        val matches = eligible.filter { identity == null || it.identity == identity }
        if (identity != null && matches.size > 1) return reject(FormReason.AmbiguousDevice)
        val found = matches.singleOrNull() ?: return reject(FormReason.ChooseDevice)
        val existing = replaceDevice?.let { id ->
            if (!canRebind(id)) return reject(FormReason.ReconcileDevice)
            if (
                ui.value.devices.devices.none {
                    it.id == id && it.status == DeviceStatus.NeedsSetup
                }
            )
                return reject(FormReason.Busy)
            cfg.rows.value.singleOrNull { it.ref.id == id && it.ref.family == found.family }
                ?: return reject(FormReason.ChooseDevice)
        }
        if (
            cfg.rows.value.any {
                it.ref.id != replaceDevice &&
                    it.address?.let(::bridgeAddress)?.toASCIIString() == key &&
                    it.bridgeIdentity == found.identity
            }
        )
            return reject(FormReason.Duplicate)
        val kind =
            if (uri.host in setOf("127.0.0.1", "localhost", "[::1]")) ConnectionKind.IntifaceOnPhone
            else ConnectionKind.IntifaceOnNetwork
        if (existing == null) {
            cfg.save(
                SavedDevice(
                    DeviceRef(
                        DeviceId(UUID.randomUUID().toString()),
                        found.name,
                        found.family,
                        kind,
                    ),
                    defaultDeviceSettings(found.family),
                    address = key,
                    bridgeIdentity = found.identity,
                )
            )
        } else {
            cfg.update(existing.ref.id) { row ->
                row.copy(
                    ref = row.ref.copy(connection = kind),
                    address = key,
                    deviceIndex = null,
                    bridgeIdentity = found.identity,
                )
            }
        }
        refresh(cfg.rows.value)
        if (existing != null)
            ui.update { state ->
                state.copy(
                    devices =
                        state.devices.copy(
                            devices =
                                state.devices.devices.map { row ->
                                    if (row.id == existing.ref.id)
                                        row.copy(status = DeviceStatus.NotConnected)
                                    else row
                                }
                        )
                )
            }
        return FormResult.Accepted
    }
}

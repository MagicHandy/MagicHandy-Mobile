package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Resolves the last visible choice to a stable identity before saving a named bridge device. */
internal class BridgeSetup(
    private val d: CoreDependencies,
    private val ui: MutableStateFlow<AppState>,
    private val refresh: (List<SavedDevice>) -> Unit,
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

    private suspend fun discover(address: String, linear: Boolean): List<BridgeTarget> {
        if (!connections.tryAcquire()) return emptyList()
        return try {
            lookup(address, linear)
        } finally {
            connections.release()
        }
    }

    private suspend fun lookup(address: String, linear: Boolean): List<BridgeTarget> {
        val cfg = d.devices.configuration ?: return emptyList()
        val uri = bridgeAddress(address) ?: return emptyList()
        if (!ui.value.firstRun.hasAcknowledgedAdultsOnly) return emptyList()
        val key = uri.toASCIIString()
        val request =
            synchronized(listings) {
                val last = listings[linear]?.takeIf { it.address == key } ?: Listing(key, 0)
                (++next).also { listings[linear] = last.copy(request = it) }
            }
        val list =
            try {
                withTimeoutOrNull(8000) { withContext(d.dispatchers.io) { cfg.bridgeTargets(key) } }
                    .orEmpty()
            } catch (_: java.io.IOException) {
                DiagnosticReporter.record(DiagnosticKind.DeviceConnectionFailed)
                emptyList()
            }
        val valid = list.filter { (it.family != DeviceFamily.Vibrator) == linear }
        return synchronized(listings) {
            val last = listings[linear] ?: return@synchronized emptyList()
            if (last.request != request) return@synchronized emptyList()
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
            valid
        }
    }

    suspend fun vibratorsAt(address: String) =
        discover(address, false).map { IntifaceChoice(it.deviceIndex, it.name, it.identity) }

    suspend fun linearsAt(address: String) =
        discover(address, true).map {
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
        if (
            uri.scheme !in setOf("ws", "wss") ||
                uri.host == null ||
                uri.userInfo != null ||
                uri.query != null ||
                uri.fragment != null
        )
            return null
        if (uri.port == 0 || uri.port > 65535) return null
        val port =
            uri.port.takeUnless {
                it < 0 || it == 80 && uri.scheme == "ws" || it == 443 && uri.scheme == "wss"
            }
        return URI(
                uri.scheme +
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
        val found =
            eligible.singleOrNull {
                selection?.let { token -> it.identity == token }
                    ?: (selected == null || it.identity == selected.identity)
            } ?: return reject(FormReason.ChooseDevice)
        if (
            cfg.rows.value.any {
                it.address?.let(::bridgeAddress)?.toASCIIString() == key &&
                    it.bridgeIdentity == found.identity
            }
        )
            return reject(FormReason.Duplicate)
        val kind =
            if (uri.host in setOf("127.0.0.1", "localhost", "[::1]")) ConnectionKind.IntifaceOnPhone
            else ConnectionKind.IntifaceOnNetwork
        cfg.save(
            SavedDevice(
                DeviceRef(DeviceId(UUID.randomUUID().toString()), found.name, found.family, kind),
                defaultDeviceSettings(found.family),
                address = key,
                bridgeIdentity = found.identity,
            )
        )
        refresh(cfg.rows.value)
        return FormResult.Accepted
    }
}

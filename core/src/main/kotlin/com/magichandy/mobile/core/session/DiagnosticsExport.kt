// The allowlist follows desktop internal/transport/types.go SafeCommandResult and ADR 0043.
package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Collect only typed, redacted receipt evidence. The session dispatcher owns observation. */
internal class DiagnosticsExport(private val d: CoreDependencies) {
    private data class Receipt(
        val epoch: Epoch,
        val header: String,
        val rows: Map<DeviceId, String>,
    )

    private val stops = MutableStateFlow<List<Receipt>>(emptyList())
    private var previous: StopReceipt? = null

    fun observe(epoch: Epoch, receipt: StopReceipt?) {
        if (receipt == null || previous === receipt) return
        previous = receipt
        val header =
            "Stop at " + receipt.at.toEpochMilliseconds() + "  presses=" + receipt.pressCount
        val rows =
            receipt.outcomes.take(64).associate { row ->
                row.device to
                    ("  " +
                        row.family.name +
                        " " +
                        row.connection.name +
                        " " +
                        row.outcome.name +
                        row.cause?.let { " " + it.name }.orEmpty() +
                        row.confirmedIn?.let { " " + it.inWholeMilliseconds + " ms" }.orEmpty())
            }
        stops.update { old ->
            val latest = old.lastOrNull { it.epoch == epoch }
            val snapshot = Receipt(epoch, header, latest?.rows.orEmpty() + rows)
            (old.filterNot { it.epoch == epoch } + snapshot).takeLast(16)
        }
    }

    suspend fun report(state: AppState): DiagnosticsReport =
        withContext(d.dispatchers.io) {
            val out = StringBuilder()
            // Every interpolated field is numeric, an enum, or a validated build-time version.
            fun line(value: String) {
                if (out.length + value.length + 1 <= 256 * 1024) out.append(value).append('\n')
            }
            line("MagicHandy diagnostics v1")
            val version =
                d.versionName.takeIf {
                    it == "development" || it.matches(Regex("""[0-9]{1,8}(\.[0-9]{1,8}){1,3}"""))
                } ?: "unknown"
            line("App version " + version)
            d.androidApi?.let { line("Android API " + it) }
            line("Run " + state.run.phase.name + "  Autopilot " + state.autopilot.status.name)
            line("Saved devices " + state.devices.devices.size)
            state.devices.devices.take(128).forEach {
                line("  " + it.family.name + " " + it.connection.name + " " + it.status.name)
            }
            try {
                val j = d.journal.read()
                line(
                    if (j == null) "Run journal clear"
                    else
                        "Run journal armed  intent=" +
                            j.intended.name +
                            "  devices=" +
                            j.devices.size +
                            "  version=" +
                            j.version
                )
            } catch (_: IOException) {
                line("Run journal unavailable")
            }
            line("Recent Stop evidence")
            stops.value.forEach {
                line(it.header)
                it.rows.values.take(64).forEach(::line)
            }
            line("Recent diagnostic events")
            try {
                d.diagnostics.recent(2048).takeLast(2048).forEach {
                    line(it.at.toEpochMilliseconds().toString() + " " + it.kind.name)
                }
            } catch (_: IOException) {
                line("Diagnostic storage unavailable")
            }
            DiagnosticsReport(
                "MagicHandy-diagnostics-" + d.clock.now().toEpochMilliseconds() + ".txt",
                out.toString(),
            )
        }
}

// Ported from internal/transport/{autoblow_air,autoblow_valves}.go at 1b80d72e.
package com.magichandy.mobile.transport.autoblow

import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.OutputSlice
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class HaltResult {
    Confirmed,
    FencedBeforeWrite,
    Unconfirmed,
}

internal fun combineHalt(first: HaltResult, second: HaltResult): HaltResult =
    when {
        first == HaltResult.Confirmed && second == HaltResult.Confirmed -> HaltResult.Confirmed
        first == HaltResult.FencedBeforeWrite && second == HaltResult.FencedBeforeWrite ->
            HaltResult.FencedBeforeWrite
        else -> HaltResult.Unconfirmed
    }

internal enum class ValveOutcome {
    NoOp,
    Applied,
    FencedBeforeWrite,
    FencedAfterWrite,
}

internal class ValveSteps(private val wire: AutoblowWire, private val permitted: () -> Boolean) {
    private var epoch: Epoch? = null
    private var consumed = 0L
    private var balance = 0
    private var connectionBalance = 0
    private var generation = -1L

    suspend fun apply(slice: OutputSlice, fence: OutputFence): ValveOutcome {
        if (epoch != fence.epoch) {
            epoch = fence.epoch
            balance = 0
            consumed = 0
        }
        if (generation != fence.streamGeneration) {
            generation = fence.streamGeneration
            consumed = 0
        }
        if (slice.airSteps == 0 || slice.airRevision <= consumed) return ValveOutcome.NoOp
        consumed = slice.airRevision
        val count = slice.airSteps
        if (
            !permitted() ||
                count !in -2..2 ||
                balance + count !in -3..3 ||
                connectionBalance + count !in -12..12
        )
            return ValveOutcome.NoOp
        val valve = if (count > 0) "stroke-plus" else "stroke-minus"
        var sentAny = false
        fun fencedOutcome(): ValveOutcome =
            when {
                !sentAny -> ValveOutcome.FencedBeforeWrite
                fence.isCurrent() -> ValveOutcome.Applied
                else -> ValveOutcome.FencedAfterWrite
            }
        repeat(kotlin.math.abs(count)) {
            if (!fence.isCurrent() || !permitted()) return fencedOutcome()
            val step = if (count > 0) 1 else -1
            val denied = AtomicBoolean()
            var needsClose = true
            try {
                val opened =
                    try {
                        wire.put(
                            "valve/" + valve,
                            buildJsonObject { put("valveState", true) },
                            false,
                        ) {
                            (fence.isCurrent() && permitted()).also { if (!it) denied.set(true) }
                        }
                        !denied.get()
                    } catch (failure: IOException) {
                        if (failure.isFencedAutoblow() || denied.get()) false else throw failure
                    }
                if (!opened) {
                    needsClose = false
                    DiagnosticReporter.record(DiagnosticKind.WriteFenced)
                    return fencedOutcome()
                }
                sentAny = true
                balance += step
                connectionBalance += step
                delay(300)
            } finally {
                if (needsClose) {
                    withContext(NonCancellable) {
                        if (close(valve) { true } != HaltResult.Confirmed)
                            throw IOException("Valve closure unconfirmed")
                    }
                }
            }
        }
        return ValveOutcome.Applied
    }

    suspend fun settle(allowed: () -> Boolean): HaltResult = coroutineScope {
        val plus = async { close("stroke-plus", allowed) }
        val minus = close("stroke-minus", allowed)
        combineHalt(plus.await(), minus)
    }

    private suspend fun close(valve: String, allowed: () -> Boolean): HaltResult {
        val denied = AtomicBoolean()
        return try {
            val state =
                wire.put(
                    "valve/" + valve,
                    buildJsonObject { put("valveState", false) },
                    true,
                    { allowed().also { if (!it) denied.set(true) } },
                )
            if (denied.get()) HaltResult.FencedBeforeWrite
            else {
                val field = if (valve == "stroke-plus") "strokePlusValve" else "strokeMinusValve"
                if (
                    state[field] == JsonPrimitive(false) ||
                        allowed() && wire.state(priorityRead = true)[field] == JsonPrimitive(false)
                )
                    HaltResult.Confirmed
                else HaltResult.Unconfirmed
            }
        } catch (failure: IOException) {
            if (failure.isFencedAutoblow() || denied.get()) HaltResult.FencedBeforeWrite
            else HaltResult.Unconfirmed
        }
    }
}

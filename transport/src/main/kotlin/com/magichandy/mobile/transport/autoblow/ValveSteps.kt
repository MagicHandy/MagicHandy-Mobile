// Ported from internal/transport/{autoblow_air,autoblow_valves}.go at 1b80d72e.
package com.magichandy.mobile.transport.autoblow

import com.magichandy.mobile.core.ports.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal class ValveSteps(private val wire: AutoblowWire, private val permitted: () -> Boolean) {
    private var epoch: Epoch? = null
    private var consumed = 0L
    private var balance = 0
    private var connectionBalance = 0
    private var generation = -1L

    suspend fun apply(slice: OutputSlice, fence: OutputFence) {
        if (epoch != fence.epoch) {
            epoch = fence.epoch
            balance = 0
            consumed = 0
        }
        if (generation != fence.streamGeneration) {
            generation = fence.streamGeneration
            consumed = 0
        }
        if (slice.airSteps == 0 || slice.airRevision <= consumed) return
        consumed = slice.airRevision
        val count = slice.airSteps
        if (
            !permitted() ||
                count !in -2..2 ||
                balance + count !in -3..3 ||
                connectionBalance + count !in -12..12
        )
            return
        val valve = if (count > 0) "stroke-plus" else "stroke-minus"
        repeat(kotlin.math.abs(count)) {
            if (!fence.isCurrent() || !permitted()) return
            val step = if (count > 0) 1 else -1
            balance += step
            connectionBalance += step
            try {
                wire.put("valve/" + valve, buildJsonObject { put("valveState", true) }, false) {
                    fence.isCurrent() && permitted()
                }
                delay(300)
            } finally {
                withContext(NonCancellable) {
                    if (!close(valve) { true }) throw IOException("Valve closure unconfirmed")
                }
            }
        }
    }

    suspend fun settle(allowed: () -> Boolean): Boolean = coroutineScope {
        val plus = async { close("stroke-plus", allowed) }
        val minus = close("stroke-minus", allowed)
        plus.await() && minus
    }

    private suspend fun close(valve: String, allowed: () -> Boolean): Boolean =
        try {
            val state =
                wire.put(
                    "valve/" + valve,
                    buildJsonObject { put("valveState", false) },
                    true,
                    allowed,
                )
            val field = if (valve == "stroke-plus") "strokePlusValve" else "strokeMinusValve"
            state[field] == JsonPrimitive(false) ||
                allowed() && wire.state(priorityRead = true)[field] == JsonPrimitive(false)
        } catch (_: IOException) {
            false
        }
}

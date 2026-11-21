package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.transport.autoblow.AutoblowWire
import com.magichandy.mobile.transport.autoblow.FencedAutoblowRequest
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** In-memory vendor peer. The production driver owns tempo mapping, valve timing and Stop. */
internal class SimulatedVacuGlide(
    private val online: () -> Boolean,
    private val speed: () -> Percent,
    private val motor: (Int) -> Unit,
    private val stopMotor: () -> Unit,
) : AutoblowWire {
    private var plus = false
    private var minus = false

    override suspend fun state(priorityRead: Boolean): JsonObject {
        if (!online()) throw IOException("Simulated link lost")
        return buildJsonObject {
            put(
                "operationalMode",
                if (speed().value == 0) "ONLINE_CONNECTED" else "TARGET_SPEED_PLAYING",
            )
            put("strokePlusValve", plus)
            put("strokeMinusValve", minus)
        }
    }

    override suspend fun events(opened: () -> Unit, receive: (String) -> Unit): Unit {
        opened()
        awaitCancellation()
    }

    override suspend fun put(
        path: String,
        body: JsonObject?,
        stop: Boolean,
        allowed: () -> Boolean,
    ): JsonObject {
        if (!allowed()) throw FencedAutoblowRequest()
        if (!online()) throw IOException("Simulated link lost")
        when (path) {
            "target-speed" -> motor(requireNotNull(body).getValue("targetSpeed").jsonPrimitive.int)
            "target-speed/stop" -> stopMotor()
            "valve/stroke-plus" ->
                plus = requireNotNull(body).getValue("valveState").jsonPrimitive.boolean
            "valve/stroke-minus" ->
                minus = requireNotNull(body).getValue("valveState").jsonPrimitive.boolean
            else -> throw IOException("Unknown simulated command")
        }
        return state()
    }
}

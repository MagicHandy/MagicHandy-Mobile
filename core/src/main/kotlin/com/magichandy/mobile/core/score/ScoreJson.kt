// Ported from internal/motion/flow.go at 1b80d72e.
package com.magichandy.mobile.core.score

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal fun LayeredScore.toJson(): JsonObject = buildJsonObject {
    put("min_percent", low)
    put("max_percent", high)
    put("speed_percent", speed)
    put("range_floor_percent", floor)
    if (ceiling > 0) put("range_ceiling_percent", ceiling)
    put("anchor_percent", anchor)
    put("memory_cycles", memory)
    put("pace_variation_percent", paceVariation)
    put("variation_mode", if (drift) "drift" else "waves")
    put("seed", seed.toLong())
    if (softness > 0) put("turn_softness_percent", softness)
    if (cadence > 0) put("cadence_hold_percent", cadence)
    if (cycles != 64) put("loop_cycles", cycles)
    if (upRate != 100 || downRate != 100)
        put(
            "directional_pace",
            buildJsonObject {
                put("up_rate_percent", upRate)
                put("down_rate_percent", downRate)
            },
        )
    if (layers.isNotEmpty()) put("layers", JsonArray(layers.map { it.toJson() }))
    if (groups.isNotEmpty())
        put(
            "phrase",
            buildJsonObject {
                put(
                    if (groups.first().cycle == null) "strokes" else "regions",
                    JsonArray(
                        groups.map { g ->
                            buildJsonObject {
                                put("bottom_percent", g.bottom)
                                put("top_percent", g.top)
                                put("count", g.count)
                                put("rate_percent", g.rate)
                                g.cycle?.let {
                                    put(
                                        "cycle",
                                        if (it == RegionCycle.UpDown) "up_down" else "down_up",
                                    )
                                }
                            }
                        }
                    ),
                )
            },
        )
}

internal fun FlowLayer.toJson() = buildJsonObject {
    put("axis", axis.name.lowercase())
    put("shape", shape.name.lowercase())
    put("amount_percent", amount)
    put("period_cycles", period)
    put("phase_percent", phase)
    if (lowRate > 0) put("low_rate_percent", lowRate)
    if (inverted) put("inverted", true)
}

internal fun JsonObject.layeredScore(): LayeredScore {
    fun n(k: String, v: Int) = this[k]?.jsonPrimitive?.int ?: v
    val pace = this["directional_pace"]?.jsonObject
    val groups = this["phrase"]?.jsonObject?.let(::readGroups).orEmpty()
    return LayeredScore(
        n("min_percent", 5),
        n("max_percent", 95),
        n("speed_percent", 25),
        n("range_floor_percent", 20),
        n("range_ceiling_percent", 0),
        n("anchor_percent", 50),
        n("memory_cycles", 10),
        n("pace_variation_percent", 12),
        this["variation_mode"]?.jsonPrimitive?.content == "drift",
        n("turn_softness_percent", 0),
        n("cadence_hold_percent", 0),
        (this["seed"]?.jsonPrimitive?.long ?: 17).also { require(it in 1..0xffffffffL) }.toUInt(),
        pace?.get("up_rate_percent")?.jsonPrimitive?.int ?: 100,
        pace?.get("down_rate_percent")?.jsonPrimitive?.int ?: 100,
        n("loop_cycles", 64),
        this["layers"]?.jsonArray?.map { it.jsonObject.flowLayer() }.orEmpty(),
        groups,
    )
}

internal fun JsonObject.flowLayer(): FlowLayer {
    fun n(k: String, v: Int) = this[k]?.jsonPrimitive?.int ?: v
    val axis =
        LayerAxis.entries.single { it.name.equals(getValue("axis").jsonPrimitive.content, true) }
    val shape =
        LayerShape.entries.single {
            it.name.equals(this["shape"]?.jsonPrimitive?.content ?: "wave", true)
        }
    return FlowLayer(
        axis,
        n("amount_percent", 0),
        n("period_cycles", 8),
        n("phase_percent", 0),
        shape,
        n("low_rate_percent", 0),
        this["inverted"]?.jsonPrimitive?.boolean ?: false,
    )
}

internal fun readGroups(phrase: JsonObject): List<PhraseGroup> {
    require(phrase.keys == setOf("regions") || phrase.keys == setOf("strokes"))
    val regional = "regions" in phrase
    val entries = phrase.values.single().jsonArray
    require(entries.size in 1..6)
    return entries.map { item ->
        val obj = item.jsonObject
        fun n(key: String) = obj.getValue(key).jsonPrimitive.int
        val dir =
            if (regional)
                when (obj.getValue("cycle").jsonPrimitive.content) {
                    "up_down" -> RegionCycle.UpDown
                    "down_up" -> RegionCycle.DownUp
                    else -> throw IllegalArgumentException("Unknown region direction")
                }
            else null
        PhraseGroup(n("bottom_percent"), n("top_percent"), n("count"), n("rate_percent"), dir)
    }
}

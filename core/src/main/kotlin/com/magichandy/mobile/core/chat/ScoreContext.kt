package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.score.*
import kotlinx.serialization.json.*

internal fun SemanticScore.context(template: JsonObject): JsonObject =
    when (this) {
        is GestureScore ->
            buildJsonObject {
                put(
                    "focus",
                    buildJsonObject {
                        put("mix_percent", mix)
                        put("position_percent", focus)
                        put("roam_percent", roam)
                        put("width_percent", width)
                    },
                )
                put("inertia_percent", inertia)
                put(
                    "range",
                    buildJsonObject {
                        put("max_percent", high)
                        put("min_percent", low)
                    },
                )
                put(
                    "rebounds",
                    buildJsonObject {
                        put("count", rebounds)
                        put("retained_width_percent", decay)
                    },
                )
                put("speed_percent", speed)
                put(
                    "sweep",
                    buildJsonObject {
                        put("contrast_percent", contrast)
                        put("faster_direction", faster.name.lowercase())
                    },
                )
                put("variation_percent", variation)
            }
        is LayeredScore -> {
            val flat = toJson()
            val values = buildJsonObject {
                put(
                    "controls",
                    buildJsonObject {
                        for (key in template.getValue("controls").jsonObject.keys) put(
                            key,
                            flat.getValue(key),
                        )
                    },
                )
                if ("range" in template)
                    put(
                        "range",
                        buildJsonObject {
                            put("max_percent", high)
                            put("min_percent", low)
                        },
                    )
                val advanced = "shortest_percent" in template.getValue("stroke_width").jsonObject
                put(
                    "stroke_width",
                    buildJsonObject {
                        put(if (advanced) "shortest_percent" else "min_percent", floor)
                        put(
                            if (advanced) "widest_percent" else "max_percent",
                            if (ceiling == 0) high - low else ceiling,
                        )
                    },
                )
                put(
                    "layers",
                    JsonArray(
                        layers.map { l ->
                            buildJsonObject {
                                put("axis", l.axis.name.lowercase())
                                put("amount_percent", l.amount)
                                put("period_cycles", l.period)
                                put("phase_percent", l.phase)
                                put("shape", l.shape.name.lowercase())
                                if (l.lowRate > 0) put("low_rate_percent", l.lowRate)
                                if (l.inverted) put("inverted", true)
                            }
                        }
                    ),
                )
                for (key in listOf("directional_pace", "phrase")) flat[key]?.let { put(key, it) }
            }
            JsonObject(values.toSortedMap())
        }
    }

internal fun JsonElement.withSpeedBounds(
    minimum: Int,
    maximum: Int,
    speed: Boolean = false,
): JsonElement =
    when (this) {
        is JsonArray -> JsonArray(map { it.withSpeedBounds(minimum, maximum, speed) })
        is JsonObject -> {
            if (speed && get("minimum") == JsonPrimitive(10) && get("maximum") == JsonPrimitive(43))
                JsonObject(
                    this +
                        mapOf(
                            "minimum" to JsonPrimitive(minimum),
                            "maximum" to JsonPrimitive(maximum),
                        )
                )
            else
                JsonObject(
                    mapValues { (k, v) ->
                        v.withSpeedBounds(minimum, maximum, speed || k == "speed_percent")
                    }
                )
        }
        else -> this
    }

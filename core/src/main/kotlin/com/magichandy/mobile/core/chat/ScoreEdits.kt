// Ported from internal/chat/{hosted_layered,layered_geometry,creative_v2}.go at 1b80d72e.
package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.score.*
import kotlinx.serialization.json.*

internal fun JsonElement.editGroups(): JsonObject {
    if (this is JsonObject) return this
    val result = mutableMapOf<String, JsonElement>()
    for (group in jsonArray) for ((key, value) in group.jsonObject) require(
        result.put(key, value) == null
    )
    return JsonObject(result)
}

internal fun GestureScore.edited(edits: JsonObject): GestureScore {
    val focusEdit = edits["focus"]?.jsonObject
    val range = edits["range"]?.jsonObject
    val rebound = edits["rebounds"]?.jsonObject
    val sweep = edits["sweep"]?.jsonObject
    return copy(
        low = range?.num("min_percent", low) ?: low,
        high = range?.num("max_percent", high) ?: high,
        speed = edits.num("speed_percent", speed),
        focus = focusEdit?.num("position_percent", focus) ?: focus,
        width = focusEdit?.num("width_percent", width) ?: width,
        roam = focusEdit?.num("roam_percent", roam) ?: roam,
        mix = focusEdit?.num("mix_percent", mix) ?: mix,
        rebounds = rebound?.num("count", rebounds) ?: rebounds,
        decay = rebound?.num("retained_width_percent", decay) ?: decay,
        contrast = sweep?.num("contrast_percent", contrast) ?: contrast,
        faster =
            sweep?.word("faster_direction")?.let { word ->
                SweepDirection.entries.single { it.name.equals(word, true) }
            } ?: faster,
        variation = edits.num("variation_percent", variation),
        inertia = edits.num("inertia_percent", inertia),
        seed = if (edits["evolve"] == JsonPrimitive(true)) freshSeed() else seed,
    )
}

private fun operation(old: JsonElement?, v: JsonElement): JsonElement {
    if (v !is JsonObject) return v
    val change = v.getValue("value")
    return when (v.word("op")) {
        "set" -> change
        "adjust" ->
            JsonPrimitive(
                Math.addExact(requireNotNull(old).jsonPrimitive.int, change.jsonPrimitive.int)
            )
        else -> throw IllegalArgumentException("Unknown control operation")
    }
}

internal fun LayeredScore.edited(edits: JsonObject): LayeredScore {
    val values = toJson().toMutableMap()
    val controls = edits["controls"]?.jsonObject.orEmpty() + edits["range"]?.jsonObject.orEmpty()
    for ((k, v) in controls) values[k] = operation(values[k], v)
    for ((k, v) in edits["change_by"]?.jsonObject.orEmpty()) {
        require(k !in controls)
        values[k] =
            JsonPrimitive(Math.addExact(values.getValue(k).jsonPrimitive.int, v.jsonPrimitive.int))
    }
    edits["speed_percent"]?.let { values["speed_percent"] = operation(values["speed_percent"], it) }
    val geometry =
        edits.word("geometry")
            ?: edits.word("composition")?.takeIf { it == "full_band" || it == "full_slider" }
    val axes = layers.associateBy { it.axis.name.lowercase() }.toMutableMap()
    if (geometry != null) applyGeometry(geometry, values, axes)
    for ((k, v) in edits["stroke_width"]?.jsonObject.orEmpty()) values[
        if (k == "shortest_percent" || k == "min_percent") "range_floor_percent"
        else "range_ceiling_percent"] = v
    edits["directional_pace"]?.let { values["directional_pace"] = it }
    edits["phrase"]?.let { phrase ->
        val groups = readGroups(phrase.jsonObject)
        val low = groups.minOf { it.bottom }
        val high = groups.maxOf { it.top }
        values["phrase"] = phrase
        values["min_percent"] = JsonPrimitive(low)
        values["max_percent"] = JsonPrimitive(high)
        values["range_floor_percent"] = JsonPrimitive(10)
        values.remove("range_ceiling_percent")
        axes.keys.retainAll(setOf("pace"))
    }
    if (geometry != null) values.remove("phrase")
    if (edits["evolve"] == JsonPrimitive(true)) values["seed"] = JsonPrimitive(freshSeed().toLong())
    edits["remove_layers"]?.jsonArray?.forEach { axes.remove(it.jsonPrimitive.content) }
    val layerEdit = edits["layers"]
    if (layerEdit is JsonArray)
        for (entry in layerEdit) {
            val obj = entry.jsonObject
            val axis = requireNotNull(obj.word("axis"))
            updateLayer(axes, axis, obj)
        }
    if (layerEdit is JsonObject)
        for ((axis, v) in layerEdit.entries.sortedBy { it.key == "pace" }) {
            val op = v.jsonObject
            when (op.word("op")) {
                "remove" -> axes.remove(axis)
                "edit" -> updateLayer(axes, axis, op.getValue("value").jsonObject)
                "follow" -> {
                    val spec = op.getValue("value").jsonObject
                    val source = axes.getValue(requireNotNull(spec.word("axis")))
                    axes["pace"] =
                        source.copy(
                            axis = LayerAxis.Pace,
                            amount = spec.getValue("amount_percent").jsonPrimitive.int,
                            lowRate = spec.getValue("low_rate_percent").jsonPrimitive.int,
                            inverted = spec.word("relation") == "opposite",
                        )
                }
                else -> throw IllegalArgumentException("Unknown layer operation")
            }
        }
    values["layers"] = JsonArray(axes.values.map { it.toJson() })
    return JsonObject(values).layeredScore()
}

private fun updateLayer(axes: MutableMap<String, FlowLayer>, axis: String, edit: JsonObject) {
    val prior =
        axes[axis] ?: FlowLayer(LayerAxis.entries.single { it.name.equals(axis, true) }, 0, 8)
    val fields = prior.toJson().toMutableMap()
    for ((key, value) in edit) fields[key] = operation(fields[key], value)
    axes[axis] = JsonObject(fields).flowLayer()
}

private fun applyGeometry(
    name: String,
    values: MutableMap<String, JsonElement>,
    axes: MutableMap<String, FlowLayer>,
) {
    fun num(key: String) = values.getValue(key).jsonPrimitive.int
    fun put(key: String, i: Int) {
        values[key] = JsonPrimitive(i)
    }
    if (name == "full_slider" || name.startsWith("full_slider_and")) {
        put("min_percent", 0)
        put("max_percent", 100)
    }
    val width = num("max_percent") - num("min_percent")
    require(width >= 10)
    fun widths(low: Int, high: Int) {
        put("range_floor_percent", low)
        put("range_ceiling_percent", high)
    }
    when (name) {
        "full_band",
        "full_slider" -> {
            widths(width, width)
            axes.remove("range")
            axes.remove("center")
        }
        "alternate_ends" -> {
            require(width >= 20)
            widths(minOf(15, width / 3).coerceAtLeast(10), minOf(30, width / 2))
            axes.remove("range")
            axes["center"] = FlowLayer(LayerAxis.Center, 100, 8, shape = LayerShape.Alternate)
        }
        "full_and_tip",
        "full_and_base",
        "full_slider_and_tip",
        "full_slider_and_base",
        "full_band_and_tip",
        "full_band_and_base" -> {
            require(width >= 20)
            put("anchor_percent", if (name.endsWith("tip")) 100 else 0)
            widths(minOf(20, width / 2), width)
            axes.remove("center")
            axes["range"] = FlowLayer(LayerAxis.Range, 100, 8, shape = LayerShape.Alternate)
        }
        "tip_anchor",
        "base_anchor",
        "centered" -> {
            put(
                "anchor_percent",
                when (name) {
                    "tip_anchor" -> 100
                    "base_anchor" -> 0
                    else -> 50
                },
            )
            put("range_floor_percent", minOf(width, num("range_floor_percent")))
            values["range_ceiling_percent"]?.let {
                put("range_ceiling_percent", minOf(width, it.jsonPrimitive.int))
            }
            axes.remove("center")
        }
        "wander" -> {
            widths(minOf(width, 20), width)
            values["variation_mode"] = JsonPrimitive("drift")
            axes.remove("range")
            axes["center"] = FlowLayer(LayerAxis.Center, 35, 14, 17, LayerShape.Drift)
        }
        else -> throw IllegalArgumentException("Unknown geometry")
    }
}

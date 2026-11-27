package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.ReasoningEffort
import com.magichandy.mobile.core.api.ReasoningOptions
import com.magichandy.mobile.core.api.RouterModel
import java.io.IOException
import java.math.RoundingMode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** OpenRouter's name for a level, as `reasoning.effort` and the catalog spell it. */
internal val ReasoningEffort.wireName: String
    get() =
        when (this) {
            ReasoningEffort.Minimal -> "minimal"
            ReasoningEffort.Low -> "low"
            ReasoningEffort.Medium -> "medium"
            ReasoningEffort.High -> "high"
            ReasoningEffort.ExtraHigh -> "xhigh"
            ReasoningEffort.Max -> "max"
        }

/**
 * A catalog row's reasoning metadata.
 *
 * @property acceptsNone true when Off can be sent as `effort: "none"`, which some models need to
 *   stop reasoning they would otherwise do by default.
 */
internal data class RouterReasoning(val options: ReasoningOptions, val acceptsNone: Boolean)

/**
 * The reasoning a catalog row offers, or null when the model doesn't reason. Only OpenRouter's
 * documented metadata counts: `supported_efforts` null means every level, and an omitted list means
 * the model can only turn reasoning on or off.
 */
internal fun JsonObject.routerReasoning(): RouterReasoning? {
    val reasoning = this["reasoning"] as? JsonObject
    val isRequired = reasoning?.get("mandatory") == JsonPrimitive(true)
    if ("reasoning" !in supportedParameters()) {
        return if (isRequired) RouterReasoning(ReasoningOptions(emptyList(), true), false) else null
    }
    if (reasoning == null) return RouterReasoning(ReasoningOptions(emptyList(), false), false)
    val names =
        when (val efforts = reasoning["supported_efforts"]) {
            null -> emptyList()
            JsonNull -> null
            is JsonArray -> efforts.mapNotNull { it.stringOrNull() }
            else -> emptyList()
        }
    val levels =
        if (names == null) ReasoningEffort.entries.toList()
        else ReasoningEffort.entries.filter { it.wireName in names }
    val default =
        reasoning["default_effort"]?.stringOrNull()?.let { name ->
            levels.firstOrNull { it.wireName == name }
        }
    return RouterReasoning(
        ReasoningOptions(levels, isRequired, default),
        acceptsNone = !isRequired && (names == null || "none" in names),
    )
}

internal fun JsonObject.supportedParameters(): Set<String> =
    (this["supported_parameters"] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }.toSet()

/**
 * The models in a catalog reply that can chat in MagicHandy, in OpenRouter's order. Batch-only
 * variants are left out: they don't serve chat requests.
 *
 * @throws IOException when the reply isn't a catalog.
 */
internal fun routerModels(text: String): List<RouterModel> {
    val rows =
        try {
            (Json.parseToJsonElement(text) as? JsonObject)?.get("data") as? JsonArray
        } catch (_: SerializationException) {
            null
        } ?: throw IOException("Unreadable catalog")
    return rows.filterIsInstance<JsonObject>().mapNotNull { it.routerModel() }
}

private fun JsonObject.routerModel(): RouterModel? {
    val id = this["id"]?.stringOrNull() ?: return null
    if (
        id.isEmpty() ||
            id.length > 200 ||
            id.any { it.isWhitespace() || it.isISOControl() } ||
            id.endsWith(":batch") ||
            "structured_outputs" !in supportedParameters()
    )
        return null
    val pricing = this["pricing"] as? JsonObject
    return RouterModel(
        id = id,
        name = displayName(this["name"]?.stringOrNull(), id),
        contextTokens = (this["context_length"] as? JsonPrimitive)?.intOrNull ?: 0,
        reasoning = routerReasoning()?.options,
        inputMicrosPerMillion = pricing?.get("prompt")?.microsPerMillion(),
        outputMicrosPerMillion = pricing?.get("completion")?.microsPerMillion(),
    )
}

/** "DeepSeek: DeepSeek V4.1 Flash 1017" becomes "DeepSeek V4.1 Flash 1017". */
private fun displayName(name: String?, id: String): String {
    val shown = name?.substringAfter(": ")?.trim()
    return if (shown.isNullOrEmpty() || shown.length > 120) id.substringAfterLast('/') else shown
}

/** A per-token price in US dollars, such as "0.000000036", as millionths of a dollar a million. */
private fun kotlinx.serialization.json.JsonElement.microsPerMillion(): Long? {
    val price = stringOrNull()?.toBigDecimalOrNull() ?: return null
    if (price.signum() < 0) return null
    return price.movePointRight(12).setScale(0, RoundingMode.HALF_UP).toLong()
}

private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

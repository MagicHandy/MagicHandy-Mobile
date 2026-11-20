package com.magichandy.mobile.core.chat

import kotlinx.serialization.json.*

/** Reject duplicate names (including escaped aliases) before the JSON library collapses them. */
internal fun modelObject(raw: String): JsonObject {
    require(raw.length <= 65536 && raw.utf8Length() <= 65536)
    val scopes = ArrayDeque<MutableSet<String>?>()
    var i = 0
    while (i < raw.length) {
        when (raw[i]) {
            '{',
            '[' -> {
                require(scopes.size < 40)
                scopes.addLast(if (raw[i] == '{') mutableSetOf() else null)
            }
            '}',
            ']' -> {
                require(scopes.isNotEmpty())
                scopes.removeLast()
            }
            '"' -> {
                val start = i++
                while (i < raw.length && raw[i] != '"') {
                    if (raw[i] == '\\') i++
                    i++
                }
                require(i < raw.length)
                var next = i + 1
                while (next < raw.length && raw[next].isWhitespace()) next++
                if (raw.getOrNull(next) == ':') {
                    val key =
                        Json.parseToJsonElement(raw.substring(start, i + 1)).jsonPrimitive.content
                    require(scopes.lastOrNull()?.add(key) == true)
                }
            }
        }
        i++
    }
    return Json.parseToJsonElement(raw).jsonObject
}

/** The vocabulary emitted by the pinned strict-schema converter. */
internal fun JsonObject.accepts(value: JsonElement, allowOmittedNulls: Boolean = false): Boolean {
    if (
        this["const"]?.let { it != value } == true ||
            this["enum"]?.jsonArray?.contains(value) == false
    )
        return false
    for (union in listOf("anyOf", "oneOf")) this[union]?.let { choices ->
        return choices.jsonArray.any { it.jsonObject.accepts(value, allowOmittedNulls) }
    }
    return when (this["type"]?.jsonPrimitive?.content) {
        "object" -> {
            if (value !is JsonObject) return false
            val props = this["properties"]?.jsonObject.orEmpty()
            this["required"]?.jsonArray.orEmpty().all { item ->
                val key = item.jsonPrimitive.content
                key in value ||
                    allowOmittedNulls && props[key]?.jsonObject?.accepts(JsonNull) == true
            } &&
                value.all { (key, v) ->
                    props[key]?.jsonObject?.accepts(v, allowOmittedNulls)
                        ?: (this["additionalProperties"] != JsonPrimitive(false))
                }
        }
        "array" ->
            value is JsonArray &&
                value.size in
                    (this["minItems"]?.jsonPrimitive?.int ?: 0)..(this["maxItems"]
                            ?.jsonPrimitive
                            ?.int ?: 64) &&
                value.all { this["items"]?.jsonObject?.accepts(it, allowOmittedNulls) != false }
        "integer" ->
            value is JsonPrimitive &&
                !value.isString &&
                value.intOrNull?.let {
                    it in
                        (this["minimum"]?.jsonPrimitive?.int ?: Int.MIN_VALUE)..(this["maximum"]
                                ?.jsonPrimitive
                                ?.int ?: Int.MAX_VALUE)
                } == true
        "number" ->
            value is JsonPrimitive &&
                !value.isString &&
                value.doubleOrNull?.let {
                    it.isFinite() &&
                        it >= (this["minimum"]?.jsonPrimitive?.double ?: -Double.MAX_VALUE) &&
                        it <= (this["maximum"]?.jsonPrimitive?.double ?: Double.MAX_VALUE)
                } == true
        "string" ->
            value is JsonPrimitive &&
                value.isString &&
                value.content.length in
                    (this["minLength"]?.jsonPrimitive?.int ?: 0)..(this["maxLength"]
                            ?.jsonPrimitive
                            ?.int ?: 65536)
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "null" -> value === JsonNull
        null -> true
        else -> false
    }
}

internal fun JsonElement.withoutNullFields(): JsonElement =
    when (this) {
        is JsonObject ->
            JsonObject(filterValues { it != JsonNull }.mapValues { it.value.withoutNullFields() })
        is JsonArray -> JsonArray(map { it.withoutNullFields() })
        else -> this
    }

internal fun JsonObject.num(key: String, fallback: Int) = this[key]?.jsonPrimitive?.int ?: fallback

internal fun JsonObject.word(key: String) = this[key]?.jsonPrimitive?.content

internal fun JsonElement.canonical(): JsonElement =
    when (this) {
        is JsonObject -> JsonObject(toSortedMap().mapValues { it.value.canonical() })
        is JsonArray -> JsonArray(map { it.canonical() })
        else -> this
    }

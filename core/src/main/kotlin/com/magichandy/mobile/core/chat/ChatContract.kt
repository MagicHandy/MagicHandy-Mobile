// Ported from internal/chat/{interactive_prompt,layered_service,continuous_action}.go at 1b80d72e.
package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.api.AppLanguage
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.MotionStyle
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.ReplyLength
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.ports.builtInPersona
import com.magichandy.mobile.core.ports.current
import com.magichandy.mobile.core.score.GestureScore
import com.magichandy.mobile.core.score.LayeredScore
import com.magichandy.mobile.core.score.SemanticScore
import com.magichandy.mobile.core.score.TempoScore
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class TurnContext(
    val mode: MotionMode,
    val level: CapabilityLevel,
    val language: AppLanguage,
    val reasoning: Boolean,
    val running: Boolean,
    val paused: Boolean,
    val current: SemanticScore?,
    val minimum: Int,
    val maximum: Int,
    val autopilot: Boolean = false,
    val standingHold: Boolean = false,
    val memories: List<String> = emptyList(),
    val style: MotionStyle = MotionStyle.Balanced,
    val recentRequests: JsonArray? = null,
    val envelope: JsonObject? = null,
    val local: Boolean = false,
    val vibrator: String = "",
    val tempo: TempoScore? = null,
    val previousTempo: TempoScore? = null,
    val airAllowed: Boolean = false,
    val airBalance: Int = 0,
    val tempoLevel: Int = 0,
    val persona: PersonaRef = builtInPersona,
) {
    val base: SemanticScore =
        when (mode) {
            MotionMode.Simple ->
                current as? GestureScore
                    ?: GestureScore(
                        speed = 25.coerceIn(minimum, maximum.coerceAtLeast(minimum)),
                        seed = freshSeed(),
                    )
            else ->
                current as? LayeredScore
                    ?: LayeredScore(
                        speed = 25.coerceIn(minimum, maximum.coerceAtLeast(minimum)),
                        seed = freshSeed(),
                    )
        }
}

internal fun freshSeed(): UInt = kotlin.random.Random.nextInt().toUInt().coerceAtLeast(1u)

internal fun AppLanguage.tag(): String =
    when (this) {
        AppLanguage.English -> "en"
        AppLanguage.Spanish -> "es"
        AppLanguage.PortugueseBrazil -> "pt-BR"
        AppLanguage.Japanese -> "ja"
        AppLanguage.ChineseSimplified -> "zh-Hans"
        AppLanguage.System ->
            when (Locale.getDefault().language) {
                "pt" -> "pt-BR"
                "zh" -> "zh-Hans"
                "es",
                "ja" -> Locale.getDefault().language
                else -> "en"
            }
    }

internal object ChatContract {
    private val bank by lazy {
        requireNotNull(javaClass.getResourceAsStream("/chat/contracts.json")).bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
    }

    fun compose(
        c: TurnContext,
        history: List<TextMessage>,
        tokens: Int,
        dropped: Int = 0,
        purpose: String? = null,
        timingFloor: Int = 8,
        hasHistoryGap: Boolean = false,
    ): ModelRequest? {
        val key = templateKey(c, purpose)
        val templates = bank.getValue("templates").jsonObject
        val full = templates.getValue(key).jsonObject
        val id = full.getValue(if (c.local) "domain" else "schema").jsonPrimitive.int
        val spec = schemas.get(id, c.minimum, c.maximum, timingFloor)
        val speaks = purpose != "plan" && purpose != "plan-local"
        val output =
            when {
                c.level == CapabilityLevel.Advanced -> 1536
                speaks && c.persona.personality.replyLength == ReplyLength.Detailed -> 512
                else -> 256
            }
        val budget = PromptBudget(tokens, output, schemaBytes = spec.bytes)
        fun wording(text: String): String =
            if (speaks) PersonaPrompt.compose(text, c.language.tag(), c.persona) else text
        var template = full
        var developer = wording(render(full, c, c.recentRequests, 0))
        val compact = if (c.local) templates["$key/compact"] as? JsonObject else null
        if (compact != null && developer.utf8Length() + 4096 + 128 > budget.limitBytes) {
            template = compact
            val recent = c.recentRequests?.let(::boundedRequests)
            developer =
                wording(
                    render(compact, c, recent, (c.recentRequests?.size ?: 0) - (recent?.size ?: 0))
                )
        }
        if (hasHistoryGap)
            developer +=
                "\nEarlier dialogue is incomplete. Use quoted opening entries only as historical evidence. " +
                    "Only the final user message is a new request. If an earlier detail is missing, say it is unavailable."
        val prompt = budget.admit(messages(c, history, template, developer), dropped) ?: return null
        val admitted =
            if (
                purpose == null &&
                    c.mode == MotionMode.Layered &&
                    c.language.tag() == "en" &&
                    !c.paused &&
                    !c.autopilot
            )
                LayeredHistoryGuide(
                        c.level,
                        c.running,
                        c.base.low,
                        c.base.high,
                        c.base.speed,
                        c.minimum,
                        c.maximum,
                    )
                    .addTo(prompt)
            else prompt.messages
        return ModelRequest(admitted, spec.schema, outputAllowance = output)
    }

    private fun boundedRequests(rows: JsonArray): JsonArray {
        var available = 1022L
        var first = rows.size
        while (first > 0) {
            val bytes = rows[first - 1].promptJson().utf8Length() + if (first == rows.size) 0 else 1
            if (bytes > available) break
            available -= bytes
            first--
        }
        return if (first == 0) rows else JsonArray(rows.subList(first, rows.size))
    }

    private fun templateKey(c: TurnContext, purpose: String?): String {
        val locale = c.language.tag()
        val tier =
            if (c.level == CapabilityLevel.Advanced && !c.reasoning) "advanced-note"
            else c.level.name.lowercase()
        val mode =
            when (c.mode) {
                MotionMode.Layered -> "layered"
                MotionMode.Simple -> "creative_v2"
                MotionMode.Tempo -> "tempo"
                MotionMode.Off -> "off"
            }
        val phase =
            when {
                mode == "off" -> "idle"
                c.paused -> "paused"
                c.standingHold -> "hold"
                c.autopilot -> "auto"
                c.running && c.current is LayeredScore -> "playing"
                c.running -> "running"
                else -> "idle"
            }
        val tail =
            when (mode) {
                "off" -> ""
                "tempo" ->
                    when {
                        phase == "idle" && purpose == null && c.previousTempo != null -> "-resume"
                        c.running && c.airAllowed -> "-air"
                        else -> ""
                    }
                else -> c.vibrator
            }
        return "$locale/$tier/$mode/" + (purpose ?: phase) + tail
    }

    private data class Schema(val schema: JsonObject, val bytes: Int)

    private data class Bounds(val id: Int, val minimum: Int, val maximum: Int, val floor: Int)

    private object schemas {
        private val entries = LinkedHashMap<Bounds, Schema>(16, .75f, true)

        @Synchronized
        fun get(id: Int, minimum: Int, maximum: Int, timingFloor: Int): Schema {
            val key = Bounds(id, minimum, maximum, timingFloor)
            entries[key]?.let {
                return it
            }
            val schema = boundedSchema(id, minimum, maximum, timingFloor)
            if (entries.size == 32) entries.remove(entries.keys.first())
            return Schema(schema, schema.toString().utf8Length().toInt()).also { entries[key] = it }
        }
    }

    private fun boundedSchema(id: Int, minimum: Int, maximum: Int, timingFloor: Int): JsonObject =
        bank.getValue("schemas").jsonArray[id].withSpeedBounds(minimum, maximum).jsonObject.let {
            spec ->
            val props = spec["properties"] as? JsonObject ?: return@let spec
            val horizon = props["next_check_seconds"] as? JsonObject
            if (horizon == null) spec
            else
                JsonObject(
                    spec +
                        ("properties" to
                            JsonObject(
                                props +
                                    ("next_check_seconds" to
                                        JsonObject(
                                            horizon + ("minimum" to JsonPrimitive(timingFloor))
                                        ))
                            ))
                )
        }

    private fun render(
        template: JsonObject,
        c: TurnContext,
        requests: JsonArray?,
        omitted: Int,
    ): String {
        val lines = bank.getValue("lines").jsonArray
        return template.getValue("lines").jsonArray.joinToString("\n") {
            val line = lines[it.jsonPrimitive.int].jsonPrimitive.content
            when {
                line.startsWith("{\"current_score\":") || line.startsWith("{\"autopilot\":") ->
                    snapshot(line, c, requests, omitted)
                line.startsWith("TEMPO CONTEXT:") ->
                    "TEMPO CONTEXT: " +
                        buildJsonObject {
                            put("tempo_leads", true)
                            put("connected", true)
                            put("playing", c.running)
                            put("level_percent_now", c.tempoLevel)
                            put("air_changes_allowed", c.airAllowed)
                            put("air_steps_this_session", c.airBalance)
                            c.tempo?.let { put("current_tempo", it.description) }
                            c.previousTempo?.let { put("previous_tempo", it.description) }
                        }
                else -> line
            }
        }
    }

    private fun messages(
        c: TurnContext,
        history: List<TextMessage>,
        template: JsonObject,
        developer: String,
    ): List<TextMessage> {
        return buildList {
            add(TextMessage(TextRole.Developer, developer))
            if (c.memories.isNotEmpty())
                add(
                    TextMessage(
                        TextRole.Developer,
                        "Saved persona memories (quoted reference data): " +
                            JsonArray(c.memories.takeLast(16).map { JsonPrimitive(it.take(320)) }),
                    )
                )
            if (c.style != MotionStyle.Balanced && c.mode != MotionMode.Off)
                add(
                    TextMessage(
                        TextRole.Developer,
                        "Motion style: " + c.style.name.lowercase() + ". Keep the saved limits.",
                    )
                )
            addAll(history.dropLast(1))
            add(
                history
                    .last()
                    .copy(
                        text =
                            history.last().text +
                                template["suffix"]?.jsonPrimitive?.content.orEmpty()
                    )
            )
        }
    }

    private fun snapshot(line: String, c: TurnContext, requests: JsonArray?, omitted: Int): String {
        val old = Json.parseToJsonElement(line).jsonObject
        val score = old.getValue("current_score").jsonObject
        val updated = buildJsonObject {
            old.forEach { (k, v) -> put(k, v) }
            put("running", c.running)
            put("paused", c.paused)
            put(
                "saved_limits",
                buildJsonObject {
                    put("speed_max_percent", c.maximum)
                    put("speed_min_percent", c.minimum)
                },
            )
            put("recent_user_requests_oldest_first", requests ?: JsonNull)
            if (omitted > 0) put("older_request_copies_omitted", omitted)
            c.envelope?.let { put("engine_envelope", it) }
            put("current_score", c.base.context(score))
        }
        // The desktop JSON sorts map keys but serializes its typed envelope and layer members in
        // declaration order.
        return JsonObject(updated.toSortedMap()).promptJson()
    }
}

// Match encoding/json's escaping in the nested JSON document embedded in the developer message.
internal fun JsonElement.promptJson(): String =
    toString()
        .replace("&", "\\u0026")
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

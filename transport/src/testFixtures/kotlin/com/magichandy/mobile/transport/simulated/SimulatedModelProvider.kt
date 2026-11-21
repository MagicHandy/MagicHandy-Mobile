package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.SimulatedSpeaker
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.ports.builtInPersona
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Debug-only deterministic text replies. No credentials, inference runtime or network connection.
 */
public class SimulatedModelProvider
private constructor(
    private val chunks: List<String>,
    private val interval: Duration,
    private val prefix: String,
) : StructuredProvider, SimulatedSpeaker {
    public constructor(
        chunks: List<String> = listOf("I'm here. ", "We can take our time."),
        interval: Duration = 50.milliseconds,
    ) : this(
        chunks,
        interval,
        "(${builtInPersona.name} · ${builtInPersona.personality.explicitness}) ",
    )

    override fun speakingAs(name: String, level: Explicitness): SimulatedModelProvider =
        SimulatedModelProvider(chunks, interval, "($name · $level) ")

    override suspend fun plan(request: ModelRequest): TextResult {
        delay(interval)
        val fields = (request.schema["properties"] as? JsonObject).orEmpty()
        return TextResult.Completed(
            buildJsonObject {
                if ("intent" in fields) put("intent", "check in")
                if ("next" in fields) put("next", "normal")
                if ("next_check_seconds" in fields) put("next_check_seconds", 45)
                if ("edits" in fields) putJsonObject("edits") {}
                if ("reply" in fields) put("reply", prefix + "How is the pace?")
            }
                .toString()
        )
    }

    override suspend fun complete(request: ModelRequest, preview: (String) -> Unit): TextResult {
        val words = prefix + chunks.joinToString("")
        val start =
            request.messages
                .lastOrNull { it.role == TextRole.User }
                ?.text
                ?.lineSequence()
                ?.firstOrNull()
                ?.trim()
                ?.equals("start motion", ignoreCase = true) == true
        val wrapped = (request.schema["properties"] as? JsonObject)?.get("proposal") as? JsonObject
        val schema = wrapped ?: request.schema
        val choices = (schema["oneOf"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val fields =
            if (choices.isEmpty()) schema["properties"] as? JsonObject
            else {
                val available = choices.mapNotNull { it["properties"] as? JsonObject }
                val action = if (start) "start" else "none"
                available.firstOrNull {
                    (it["action"] as? JsonObject)?.get("const") == JsonPrimitive(action)
                } ?: available.firstOrNull()
            } ?: return TextResult.Interrupted
        val answer = buildJsonObject {
            put("reply", words)
            if ("action" in fields)
                put(
                    "action",
                    (fields["action"] as? JsonObject)?.get("const") ?: JsonPrimitive("none"),
                )
            if ("basis" in fields) put("basis", "Use the current limits.")
            if ("stop" in fields) put("stop", false)
            if ("stay_unchanged" in fields) put("stay_unchanged", JsonNull)
            if ("edits" in fields) putJsonObject("edits") {}
            if ("tempo" in fields && start)
                putJsonObject("tempo") {
                    put("mode", "steady")
                    put("level_percent", 25)
                }
        }
        val body =
            if (wrapped == null) answer.toString()
            else buildJsonObject { put("proposal", answer) }.toString()
        val count = chunks.size.coerceAtLeast(1)
        for (part in body.chunked((body.length + count - 1) / count)) {
            delay(interval)
            preview(part)
        }
        return TextResult.Completed(body)
    }

    override suspend fun stream(
        messages: List<TextMessage>,
        preview: (String) -> Unit,
    ): TextResult {
        for ((index, chunk) in chunks.withIndex()) {
            delay(interval)
            preview(if (index == 0) prefix + chunk else chunk)
        }
        return TextResult.Completed(prefix + chunks.joinToString(""))
    }
}

// Ported from internal/chat/{continuous_action,hosted_response_repair}.go at 1b80d72e.
package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.ports.current
import com.magichandy.mobile.core.score.GestureScore
import com.magichandy.mobile.core.score.LayeredScore
import com.magichandy.mobile.core.score.SemanticScore
import com.magichandy.mobile.core.score.TempoScore
import com.magichandy.mobile.core.score.VibrationEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal enum class StandingWish {
    Preserve,
    Hold,
    Release,
}

internal data class Proposal(
    val reply: String,
    val action: String = "none",
    val score: SemanticScore? = null,
    val hold: StandingWish = StandingWish.Preserve,
    val vibration: VibrationEnvelope? = null,
    val tempo: TempoScore? = null,
    val air: Int = 0,
)

internal sealed interface TurnAnswer {
    data class Valid(val proposal: Proposal) : TurnAnswer

    data object Declined : TurnAnswer

    data object Failed : TurnAnswer

    data class ProviderFailed(val error: ProviderReplyFailure) : TurnAnswer
}

internal fun validateProposal(raw: String, c: TurnContext, request: ModelRequest): Proposal {
    val input = modelObject(raw)
    val wrapped =
        if (
            "proposal" in (request.schema["properties"] as? JsonObject).orEmpty() &&
                "proposal" !in input
        )
            buildJsonObject { put("proposal", input) }
        else input
    require(request.schema.accepts(wrapped, c.local)) { "Outside the original schema" }
    val json = (wrapped["proposal"] ?: wrapped).withoutNullFields().jsonObject
    val reply = requireNotNull(json.word("reply")).trim()
    require(reply.isNotBlank() && reply.utf8Length() <= 12000)
    if (c.mode == MotionMode.Tempo) {
        val tempo = tempoProposal(json, c)
        val stop = json["stop"] == JsonPrimitive(true)
        require(!stop || tempo.first == null && tempo.second == 0)
        return Proposal(
            reply,
            if (stop) "stop" else "none",
            tempo = tempo.first,
            air = tempo.second,
        )
    }
    val action = json.word("action") ?: "none"
    val hold =
        when (json["stay_unchanged"]?.jsonPrimitive?.boolean) {
            true -> StandingWish.Hold
            false -> StandingWish.Release
            null -> StandingWish.Preserve
        }
    val vibration = json.vibration()
    require(vibration == null || c.vibrator.isNotEmpty() && !c.paused && action != "stop")
    val edits = json["edits"]?.editGroups() ?: JsonObject(emptyMap())
    if (action == "none" || action == "stop") {
        require(edits.isEmpty())
        return Proposal(reply, action, hold = hold, vibration = vibration)
    }
    require(
        !c.paused &&
            c.mode != MotionMode.Off &&
            c.maximum >= c.minimum &&
            ((action == "update" && c.running) || (action == "start" && !c.running))
    )
    val next =
        when (val base = c.base) {
            is LayeredScore -> base.edited(edits)
            is GestureScore -> base.edited(edits)
        }
    require(next.speed in c.minimum..c.maximum)
    return Proposal(
        reply,
        action,
        next.takeUnless { action == "update" && it == c.current },
        hold,
        vibration,
    )
}

internal suspend fun structuredTurn(
    provider: StructuredProvider,
    request: ModelRequest,
    context: TurnContext,
    current: () -> Boolean,
    preview: (String) -> Unit = {},
    allowRepair: Boolean = true,
): TurnAnswer {
    var input = request
    repeat(2) { attempt ->
        if (!current()) return TurnAnswer.Failed
        val reader = ReplyPreview()
        when (
            val result =
                provider.complete(input) { raw ->
                    if (attempt == 0 && current())
                        reader.consume(raw).takeIf { it.isNotEmpty() }?.let(preview)
                }
        ) {
            TextResult.Refused -> return TurnAnswer.Declined
            TextResult.Interrupted -> return TurnAnswer.Failed
            is TextResult.Failed -> return TurnAnswer.ProviderFailed(result.error)
            is TextResult.Completed -> {
                if (!current()) return TurnAnswer.Failed
                try {
                    return TurnAnswer.Valid(validateProposal(result.text, context, request))
                } catch (_: IllegalArgumentException) {
                    /* Complete replacement only. */
                } catch (_: NoSuchElementException) {
                    /* A required semantic group was missing. */
                } catch (_: IllegalStateException) {
                    /* JSON has a different type. */
                }
                if (
                    !allowRepair ||
                        attempt > 0 ||
                        !result.text.trimStart().startsWith("{") ||
                        result.text.utf8Length() > 65536
                )
                    return TurnAnswer.Failed
                input =
                    request.copy(
                        messages =
                            request.messages +
                                TextMessage(TextRole.Assistant, result.text) +
                                TextMessage(
                                    TextRole.User,
                                    "The preceding JSON response was rejected and none of its changes were applied. Correct that response to satisfy the original request and the unchanged response contract. Return one complete replacement JSON object. Preserve requested behavior within the saved limits. If you cannot comply with the original request, retain that decision.",
                                ),
                        preserveTail = 3,
                    )
            }
        }
    }
    return TurnAnswer.Failed
}

/** An explicit request in the person's own words is durable only when memory is enabled. */
internal fun requestedMemory(message: String): List<String> {
    val text = message.trim()
    val prefix =
        listOf(
                "remember that ",
                "remember: ",
                "remember ",
                "recuerda que ",
                "lembre que ",
                "lembre-se de que ",
                "覚えておいて：",
                "请记住",
                "记住：",
            )
            .firstOrNull { text.startsWith(it, true) } ?: return emptyList()
    return listOfNotNull(text.drop(prefix.length).trim().takeIf { it.length in 1..320 })
}

// Ported from internal/chat/{autopilot_service,autopilot_motion_facts,autopilot_timing}.go.
package com.magichandy.mobile.core.autopilot

import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.chat.ChatContract
import com.magichandy.mobile.core.chat.ConversationWindow
import com.magichandy.mobile.core.chat.TurnContext
import com.magichandy.mobile.core.chat.accepts
import com.magichandy.mobile.core.chat.context
import com.magichandy.mobile.core.chat.editGroups
import com.magichandy.mobile.core.chat.edited
import com.magichandy.mobile.core.chat.modelObject
import com.magichandy.mobile.core.chat.tempoProposal
import com.magichandy.mobile.core.chat.vibration
import com.magichandy.mobile.core.chat.withoutNullFields
import com.magichandy.mobile.core.chat.word
import com.magichandy.mobile.core.ports.PlanningProvider
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.ports.current
import com.magichandy.mobile.core.score.GestureScore
import com.magichandy.mobile.core.score.LayeredScore
import com.magichandy.mobile.core.score.SemanticScore
import com.magichandy.mobile.core.score.TempoScore
import com.magichandy.mobile.core.score.VibrationEnvelope
import kotlin.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class Decision(
    val score: SemanticScore?,
    val reply: String,
    val next: String,
    val seconds: Int?,
    val vibration: VibrationEnvelope? = null,
    val tempo: TempoScore? = null,
    val air: Int = 0,
)

/** What a planning request came to. */
internal sealed interface Planned {
    data class Step(val decision: Decision) : Planned

    /** The provider said why it couldn't plan. */
    data class Failed(val failure: ProviderReplyFailure) : Planned

    /** No usable step this time, such as a dropped connection or a reply that didn't validate. */
    data object Missed : Planned
}

internal data class PlanFacts(
    val elapsed: Duration,
    val unchanged: Duration,
    val holds: Int,
    val speeds: List<Int>,
    val finish: Duration?,
    val floor: Int,
    val purpose: String,
) {
    fun text() = buildJsonObject {
        put("elapsed_seconds", elapsed.inWholeSeconds)
        put("seconds_at_current_phrase", unchanged.inWholeSeconds)
        put("consecutive_holds", holds)
        put("recent_speeds_oldest_first", JsonArray(speeds.map(::JsonPrimitive)))
        put(
            "task",
            when (purpose) {
                "plan" -> "Prepare the next continuous stretch within the saved limits."
                "talk" -> "Decide whether a spoken check-in adds anything. Speech only."
                else -> "Decide whether to check in and whether a motion adjustment fits."
            },
        )
        finish?.let {
            put("finish_target_seconds", it.inWholeSeconds)
            put("remaining_seconds", (it - elapsed).inWholeSeconds.coerceAtLeast(0))
            put(
                "finish_guidance",
                "The strongest stretch belongs as the finish target fills, within the saved limits. Once full it continues until the person changes it. The target is not a Stop or permission to widen limits.",
            )
        }
    }
        .toString()
}

internal suspend fun decide(
    provider: PlanningProvider,
    model: SavedModel,
    context: TurnContext,
    facts: PlanFacts,
    history: List<TextMessage>,
): Planned {
    val tokens = ConversationWindow.forModel(model).contextTokens
    val request =
        ChatContract.compose(
            context,
            history + TextMessage(TextRole.User, facts.text()),
            tokens,
            purpose = if (facts.purpose == "plan" && context.local) "plan-local" else facts.purpose,
            timingFloor = facts.floor,
        ) ?: return Planned.Failed(ProviderReplyFailure(ProviderReplyFailure.Reason.ContextLimit))
    val result =
        when (val reply = provider.plan(request)) {
            is TextResult.Completed -> reply
            is TextResult.Failed -> return Planned.Failed(reply.error)
            TextResult.Interrupted,
            TextResult.Refused -> return Planned.Missed
        }
    return try {
        val raw = modelObject(result.text)
        require(request.schema.accepts(raw, context.local))
        val reply = raw.withoutNullFields().jsonObject
        val edits = reply["edits"]?.editGroups()
        val score =
            if (edits == null || edits.isEmpty()) null
            else
                when (val current = context.base) {
                    is LayeredScore -> current.edited(edits)
                    is GestureScore -> current.edited(edits)
                }
        require(score == null || score.speed in context.minimum..context.maximum)
        val speech = reply.word("reply").orEmpty().trim()
        require(speech.length <= 12000)
        Planned.Step(
            Decision(
                score
                    ?: context.base.takeIf {
                        !context.running &&
                            facts.purpose == "plan" &&
                            context.mode != MotionMode.Tempo
                    },
                speech,
                reply.word("next") ?: "normal",
                reply["next_check_seconds"]?.jsonPrimitive?.intOrNull,
                reply.vibration(),
                if (context.mode == MotionMode.Tempo) tempoProposal(reply, context).first else null,
                reply["air_steps"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        )
    } catch (_: IllegalArgumentException) {
        Planned.Missed
    } catch (_: IllegalStateException) {
        Planned.Missed
    } catch (_: NoSuchElementException) {
        Planned.Missed
    }
}

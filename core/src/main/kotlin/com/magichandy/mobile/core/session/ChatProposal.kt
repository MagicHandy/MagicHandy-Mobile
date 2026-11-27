package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.AutopilotStatus
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.EntryId
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.MotionSource
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.chat.ChatContract
import com.magichandy.mobile.core.chat.ConversationWindow
import com.magichandy.mobile.core.chat.StandingWish
import com.magichandy.mobile.core.chat.TurnAnswer
import com.magichandy.mobile.core.chat.TurnContext
import com.magichandy.mobile.core.chat.TurnHistory
import com.magichandy.mobile.core.chat.structuredTurn
import com.magichandy.mobile.core.chat.valveAllowed
import com.magichandy.mobile.core.chat.vibration
import com.magichandy.mobile.core.chat.vibratorContext
import com.magichandy.mobile.core.motion.EngineState
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.ports.current
import com.magichandy.mobile.core.ports.recentModelEntries
import com.magichandy.mobile.core.speakingProvider
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Carries the original roster, stream and settings through generation, repair and optional retry.
 */
internal sealed interface ProposalCompletion {
    data class Completed(val entry: ChatEntry.Message) : ProposalCompletion

    data object Refused : ProposalCompletion

    data object Failed : ProposalCompletion

    data class ProviderFailed(val error: ProviderReplyFailure) : ProposalCompletion
}

internal class ChatProposal(
    private val d: CoreDependencies,
    private val engine: MotionEngine,
    private val ui: StateFlow<AppState>,
    private val devices: Devices,
    private val core: MagicHandyCore,
    private val planner: Autopilot,
) {
    suspend fun complete(
        provider: StructuredProvider,
        model: SavedModel,
        history: TurnHistory,
        captured: EngineState,
        persona: PersonaRef,
        memoryOn: Boolean,
        active: () -> Boolean,
        openPreview: (admitted: () -> Boolean, reset: Boolean) -> (String) -> Unit,
    ): ProposalCompletion {
        val state = ui.value
        var fallback: SavedModel? = null
        fun chatCurrent(): Boolean {
            val alternate = fallback
            return (alternate == null ||
                d.settings.state.value.values.refusalFallback == alternate.info.id &&
                    d.models.state.value.models[alternate.info.id] == alternate &&
                    alternate.hasRoute(d.network.observe().value)) &&
                (!memoryOn || d.settings.state.value.values.memoryOn) &&
                active() &&
                engine.epoch == captured.epoch &&
                model.acceptsInFlightReply(d.models.state.value.let { it.models[it.chat] })
        }
        fun current(): Boolean {
            val now = engine.state.value
            return chatCurrent() &&
                now.streamGeneration == captured.streamGeneration &&
                now.score === captured.score &&
                now.journal?.runId == captured.journal?.runId &&
                now.members == captured.members &&
                now.owners == captured.owners &&
                state.settings.motionMode == ui.value.settings.motionMode &&
                state.run.limits == ui.value.run.limits &&
                state.devices.devices.map { it.id to it.membership } ==
                    ui.value.devices.devices.map { it.id to it.membership }
        }
        val settings = state.settings
        val turn =
            TurnContext(
                if (captured.owners.isEmpty() || state.run.limits.topSpeed.value == 0)
                    MotionMode.Off
                else settings.motionMode,
                model.info.capability,
                state.app.language,
                model.info.isReasoningOn,
                captured.members.isNotEmpty() && captured.pausedPosition == null,
                captured.pausedPosition != null,
                captured.score?.score,
                minOf(10, state.run.limits.topSpeed.value).coerceAtLeast(1),
                state.run.limits.topSpeed.value,
                autopilot = state.autopilot.status == AutopilotStatus.Active,
                standingHold = planner.isStanding,
                envelope = devices.playback.planningEnvelope(captured.members),
                memories =
                    if (memoryOn && d.settings.state.value.values.memoryOn)
                        d.chats.state.value.memory[persona.id].orEmpty()
                    else emptyList(),
                style = settings.style,
                persona = persona,
                recentRequests = recentRequests(state, history.recent),
                vibrator = state.vibratorContext(),
                tempo = captured.tempo?.plan?.score,
                previousTempo = captured.previousTempo,
                airAllowed = state.valveAllowed(),
                airBalance = captured.airBalance,
                tempoLevel = state.run.commandedPace?.value ?: 0,
                local = model.info.provider in localProviders,
            )
        fun composeRequest(
            m: SavedModel,
            t: TurnContext,
            fallbackOnly: Boolean = false,
        ): ModelRequest? {
            val window = ConversationWindow.forModel(m)
            val stored = state.chat.entries.recentModelEntries().count { it is ChatEntry.Message }
            return ChatContract.compose(
                t,
                if (fallbackOnly) history.recent.takeLast(1) else history.messages,
                window.contextTokens,
                if (fallbackOnly) 0
                else (stored - history.recent.size).coerceAtLeast(if (history.hasGap) 1 else 0),
                hasHistoryGap = history.hasGap && !fallbackOnly,
            )
        }
        val tooLarge =
            ProposalCompletion.ProviderFailed(
                ProviderReplyFailure(ProviderReplyFailure.Reason.ContextLimit)
            )
        val primaryRequest = composeRequest(model, turn) ?: return tooLarge
        var answer =
            structuredTurn(
                provider,
                primaryRequest,
                turn,
                ::chatCurrent,
                openPreview(::chatCurrent, false),
                allowRepair = true,
            )
        if (
            answer == TurnAnswer.Declined &&
                current() &&
                model.info.provider in setOf(ProviderKind.ChatGpt, ProviderKind.OpenRouter)
        ) {
            val selected = d.settings.state.value.values.refusalFallback
            val alternate =
                d.models.state.value.models[selected]?.takeIf { it.info.provider in localProviders }
                    ?: return ProposalCompletion.Refused
            fallback = alternate
            if (!current()) return ProposalCompletion.Failed
            val peer =
                d.speakingProvider(alternate, persona) as? StructuredProvider
                    ?: return ProposalCompletion.Failed
            val localContext =
                turn.copy(
                    local = true,
                    level = alternate.info.capability,
                    reasoning = alternate.info.isReasoningOn,
                    memories = emptyList(),
                    recentRequests = null,
                )
            val localRequest =
                composeRequest(alternate, localContext, fallbackOnly = true) ?: return tooLarge
            if (!current()) return ProposalCompletion.Failed
            val activeAttempt = AtomicBoolean(true)
            val fallbackAdmitted = { activeAttempt.get() && chatCurrent() }
            val fallbackPreview = openPreview(fallbackAdmitted, true)
            answer =
                whileAuthorized(alternate, activeAttempt) {
                    structuredTurn(
                        peer,
                        localRequest,
                        localContext,
                        fallbackAdmitted,
                        fallbackPreview,
                        allowRepair = false,
                    )
                }
        }
        if (answer == TurnAnswer.Declined) return ProposalCompletion.Refused
        if (answer is TurnAnswer.ProviderFailed)
            return ProposalCompletion.ProviderFailed(answer.error)
        val proposal = (answer as? TurnAnswer.Valid)?.proposal ?: return ProposalCompletion.Failed
        val receipt =
            withContext(d.dispatchers.session) {
                val controlsDevice =
                    proposal.action != "none" ||
                        proposal.hold != StandingWish.Preserve ||
                        proposal.vibration != null ||
                        proposal.tempo != null ||
                        proposal.air != 0
                if (!chatCurrent() || controlsDevice && !current()) return@withContext null
                if (proposal.action == "stop") {
                    core.emergencyStop(StopSource.ModelReply)
                    return@withContext null
                }
                val score = proposal.score
                if (
                    score != null &&
                        !devices.playback.applyScore(
                            captured.epoch,
                            score,
                            MotionSource.Chat,
                            proposal.action == "start",
                            ::current,
                        )
                )
                    return@withContext null
                val previous = captured.score?.score
                val priorPace =
                    previous?.speed?.coerceAtMost(state.run.limits.topSpeed.value)
                        ?: state.run.commandedPace?.value
                val motionReceipt = score?.strokeReceipt(previous, priorPace, state.run.limits)
                proposal.vibration?.let {
                    if (!devices.playback.applyVibration(captured.epoch, it, MotionSource.Chat))
                        return@withContext null
                }
                when (proposal.hold) {
                    StandingWish.Hold -> planner.standingHold(true)
                    StandingWish.Release -> planner.standingHold(false)
                    StandingWish.Preserve -> Unit
                }
                if (
                    (proposal.tempo != null || proposal.air != 0) &&
                        !devices.playback.applyTempo(
                            captured.epoch,
                            proposal.tempo,
                            proposal.air,
                            MotionSource.Chat,
                        )
                )
                    return@withContext null
                val accepted = engine.state.value
                if (
                    score != null && accepted.score?.score != captured.score?.score ||
                        proposal.tempo != null &&
                            accepted.tempo?.plan?.score != captured.tempo?.plan?.score ||
                        accepted.airBalance != captured.airBalance ||
                        proposal.vibration != null && accepted.vibration != captured.vibration
                )
                    planner.chatChangedMotion()
                ChatEntry.Message(
                    EntryId(java.util.UUID.randomUUID().toString()),
                    d.clock.now(),
                    Author.Persona,
                    proposal.reply,
                    motionReceipt,
                )
            }
        return receipt?.let(ProposalCompletion::Completed) ?: ProposalCompletion.Failed
    }

    /** Revoking the selected server cancels the call while it is receiving its response body. */
    private suspend fun whileAuthorized(
        model: SavedModel,
        activeAttempt: AtomicBoolean,
        ask: suspend () -> TurnAnswer,
    ): TurnAnswer = coroutineScope {
        val revoked =
            async(start = CoroutineStart.UNDISPATCHED) {
                combine(d.settings.state, d.models.state, d.network.observe()) {
                        settings,
                        models,
                        network ->
                        settings.values.refusalFallback != model.info.id ||
                            models.models[model.info.id] != model ||
                            !model.hasRoute(network)
                    }
                    .first { it }
            }
        val call = async { ask() }
        try {
            select {
                revoked.onAwait {
                    activeAttempt.set(false)
                    TurnAnswer.Failed
                }
                call.onAwait { it }
            }
        } finally {
            activeAttempt.set(false)
            call.cancel()
            revoked.cancel()
        }
    }

    private fun recentRequests(state: AppState, history: List<TextMessage>): JsonArray? {
        val count =
            history
                .asReversed()
                .asSequence()
                .drop(1)
                .filter { it.role == TextRole.User }
                .take(8)
                .count()
        if (count == 0) return null
        val rows =
            state.chat.entries
                .recentModelEntries()
                .filterIsInstance<ChatEntry.Message>()
                .filter { it.author == Author.You }
                .drop(1)
                .take(count)
                .toList()
                .asReversed()
        val now = d.clock.now()
        return JsonArray(
            rows.map {
                buildJsonObject {
                    put("said", it.text)
                    put("seconds_ago", (now - it.at).inWholeSeconds.coerceAtLeast(0))
                }
            }
        )
    }

    companion object {
        val localProviders =
            setOf(ProviderKind.Ollama, ProviderKind.LlamaCpp, ProviderKind.OpenAiCompatible)
    }
}

/**
 * Reasoning and capability are snapshotted per turn. Switching account, model or credential revokes
 * it.
 */
private fun SavedModel.acceptsInFlightReply(selected: SavedModel?): Boolean {
    if (this == selected) return true
    return info.provider == ProviderKind.OpenRouter &&
        selected != null &&
        selected.info.provider == info.provider &&
        selected.info.id == info.id &&
        selected.info.model == info.model &&
        selected.credential == credential &&
        selected.info.status == info.status
}

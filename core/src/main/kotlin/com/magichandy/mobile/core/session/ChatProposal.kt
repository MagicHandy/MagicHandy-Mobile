package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.chat.*
import com.magichandy.mobile.core.motion.EngineState
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.ports.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Carries the original roster, stream and settings through generation, repair and optional retry.
 */
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
        history: List<TextMessage>,
        captured: EngineState,
        active: () -> Boolean,
        preview: (String) -> Unit,
    ): ChatEntry.Message? {
        val state = ui.value
        var fallback: SavedModel? = null
        fun current(): Boolean {
            val now = engine.state.value
            val alternate = fallback
            return (alternate == null ||
                d.settings.state.value.values.refusalFallback == alternate.info.id &&
                    d.models.state.value.models[alternate.info.id] == alternate &&
                    alternate.hasRoute(d.network.observe().value)) &&
                (!state.settings.isMemoryOn || d.settings.state.value.values.memoryOn) &&
                active() &&
                now.epoch == captured.epoch &&
                now.streamGeneration == captured.streamGeneration &&
                now.score === captured.score &&
                now.journal?.runId == captured.journal?.runId &&
                now.members == captured.members &&
                now.owners == captured.owners &&
                state.settings.motionMode == ui.value.settings.motionMode &&
                state.run.limits == ui.value.run.limits &&
                model == d.models.state.value.models[d.models.state.value.chat]
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
                    if (settings.isMemoryOn)
                        d.chats.state.value.memory[settings.persona.id].orEmpty()
                    else emptyList(),
                style = settings.style,
                recentRequests = recentRequests(state, history),
                vibrator = state.vibratorContext(),
                tempo = captured.tempo?.plan?.score,
                previousTempo = captured.previousTempo,
                airAllowed = state.valveAllowed(),
                airBalance = captured.airBalance,
                tempoLevel = state.run.commandedPace?.value ?: 0,
                local = model.info.provider in localProviders,
            )
        suspend fun ask(peer: StructuredProvider, m: SavedModel, t: TurnContext): TurnAnswer {
            val window = ConversationWindow.named(m.info.capability, m.contextTokens)
            val stored = state.chat.entries.count { it is ChatEntry.Message }
            val req =
                ChatContract.compose(
                    t,
                    history,
                    window.contextTokens,
                    (stored - history.size).coerceAtLeast(0),
                ) ?: return TurnAnswer.Failed
            return structuredTurn(peer, req, t, ::current, preview)
        }
        var answer = ask(provider, model, turn)
        if (
            answer == TurnAnswer.Declined &&
                current() &&
                model.info.provider in setOf(ProviderKind.ChatGpt, ProviderKind.OpenRouter)
        ) {
            val selected = d.settings.state.value.values.refusalFallback
            val alternate =
                d.models.state.value.models[selected]?.takeIf { it.info.provider in localProviders }
                    ?: return null
            fallback = alternate
            if (!current()) return null
            val peer = d.provider(alternate) as? StructuredProvider ?: return null
            answer =
                ask(
                    peer,
                    alternate,
                    turn.copy(
                        local = true,
                        level = alternate.info.capability,
                        reasoning = alternate.info.isReasoningOn,
                    ),
                )
        }
        val proposal = (answer as? TurnAnswer.Valid)?.proposal ?: return null
        val receipt =
            withContext(d.dispatchers.session) {
                if (!current()) return@withContext null
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
                val changes =
                    if (score == null) emptyList()
                    else
                        listOf(
                            ValueChange(
                                ControlKind.Pace,
                                state.run.commandedPace?.value,
                                score.speed,
                            )
                        )
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
                ChatEntry.Message(
                    EntryId(java.util.UUID.randomUUID().toString()),
                    d.clock.now(),
                    Author.Persona,
                    proposal.reply,
                    CommandReceipt(changes).takeIf { changes.isNotEmpty() },
                )
            }
        return receipt
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
                .asReversed()
                .asSequence()
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

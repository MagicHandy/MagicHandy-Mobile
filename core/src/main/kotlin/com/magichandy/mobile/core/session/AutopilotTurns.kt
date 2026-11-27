package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.ChatSummary
import com.magichandy.mobile.core.api.EntryId
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.autopilot.PlanFacts
import com.magichandy.mobile.core.autopilot.Planned
import com.magichandy.mobile.core.autopilot.decide
import com.magichandy.mobile.core.chat.TurnContext
import com.magichandy.mobile.core.chat.valveAllowed
import com.magichandy.mobile.core.chat.vibratorContext
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.PlanningProvider
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.speakingProvider
import java.io.IOException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bounded planner requests, serialized with spoken check-ins, and their saved chat messages. */
internal class AutopilotTurns(
    private val d: CoreDependencies,
    private val engine: MotionEngine,
    private val devices: Devices,
    private val ui: StateFlow<AppState>,
) {
    private val providerLane = Mutex()

    suspend fun request(
        m: SavedModel,
        persona: PersonaRef,
        facts: PlanFacts,
        standing: Boolean,
    ): Planned {
        val peer = if (facts.purpose == "plan") d.provider(m) else d.speakingProvider(m, persona)
        val p = peer as? PlanningProvider ?: return Planned.Missed
        val state = ui.value
        val preferences = state.settings
        val e = engine.state.value
        val cap = state.run.limits.topSpeed.value
        val shared = d.models.state.value.chat == m.info.id
        val c =
            TurnContext(
                preferences.motionMode,
                m.info.capability,
                state.app.language,
                m.info.isReasoningOn,
                e.members.isNotEmpty(),
                e.pausedPosition != null,
                e.score?.score,
                minOf(10, cap).coerceAtLeast(1),
                cap,
                autopilot = true,
                standingHold = standing,
                envelope = devices.playback.planningEnvelope(e.members),
                local = m.info.provider in ChatProposal.localProviders,
                vibrator = state.vibratorContext(),
                tempo = e.tempo?.plan?.score,
                previousTempo = e.previousTempo,
                airAllowed = state.valveAllowed(),
                airBalance = e.airBalance,
                tempoLevel = state.run.commandedPace?.value ?: 0,
                style = preferences.style,
                persona = persona,
                memories =
                    if (shared && preferences.isMemoryOn)
                        d.chats.state.value.memory[persona.id].orEmpty()
                    else emptyList(),
            )
        val history =
            if (!shared) emptyList()
            else
                state.chat.entries
                    .asReversed()
                    .asSequence()
                    .filterIsInstance<ChatEntry.Message>()
                    .take(16)
                    .toList()
                    .asReversed()
                    .map {
                        TextMessage(
                            if (it.author == Author.You) TextRole.User else TextRole.Assistant,
                            it.text,
                        )
                    }
        return try {
            withContext(d.dispatchers.io) {
                providerLane.withLock {
                    val catalog = d.models.state.value
                    if (
                        catalog.models[catalog.planner ?: catalog.chat] != m ||
                            shared && catalog.chat != m.info.id ||
                            c.memories.isNotEmpty() && !d.settings.state.value.values.memoryOn
                    )
                        Planned.Missed
                    else decide(p, m, c, facts, history)
                }
            }
        } catch (_: IOException) {
            Planned.Missed
        }
    }

    suspend fun saveCheckIn(text: String, persona: PersonaRef, admitted: () -> Boolean) {
        try {
            val chat =
                ui.value.chat.chatId
                    ?: ChatId(java.util.UUID.randomUUID().toString()).also {
                        d.chats.create(ChatSummary(it, "Autopilot", persona.name, d.clock.now()))
                    }
            val entry =
                ChatEntry.Message(
                    EntryId(java.util.UUID.randomUUID().toString()),
                    d.clock.now(),
                    Author.Persona,
                    text,
                    null,
                    speaker = persona.name,
                )
            d.chats.append(chat, entry, persona.id, emptyList(), admitted)
        } catch (_: IOException) {
            DiagnosticReporter.record(DiagnosticKind.StoreUnavailable)
        }
    }
}

/**
 * Failures that trying again won't fix, so Autopilot holds instead of retrying until something
 * changes. The rest may pass on their own.
 */
internal val ProviderReplyFailure.needsFix: Boolean
    get() =
        when (reason) {
            ProviderReplyFailure.Reason.CredentialsRejected,
            ProviderReplyFailure.Reason.BillingRequired,
            ProviderReplyFailure.Reason.SignInRequired,
            ProviderReplyFailure.Reason.PermissionDenied,
            ProviderReplyFailure.Reason.UsageLimit,
            ProviderReplyFailure.Reason.ModelUnavailable,
            ProviderReplyFailure.Reason.ContextLimit,
            ProviderReplyFailure.Reason.RequestRejected -> true
            ProviderReplyFailure.Reason.RateLimited,
            ProviderReplyFailure.Reason.ServiceUnavailable,
            ProviderReplyFailure.Reason.UnexpectedResponse -> false
        }

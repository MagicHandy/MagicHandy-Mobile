// Ported from internal/{chatapp/workspace,httpapi/chat}.go at 1b80d72e.
package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.BackendNotice
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.ChatSummary
import com.magichandy.mobile.core.api.EntryId
import com.magichandy.mobile.core.api.InterruptCause
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.maximumMessageLength
import com.magichandy.mobile.core.chat.ConversationWindow
import com.magichandy.mobile.core.chat.requestedMemory
import com.magichandy.mobile.core.chat.turnHistory
import com.magichandy.mobile.core.motion.EndedRun
import com.magichandy.mobile.core.motion.EngineState
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.motion.WorkKind
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.ModelProvider
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.activePersona
import com.magichandy.mobile.core.ports.builtInPersona
import com.magichandy.mobile.core.speakingProvider
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ChatTurns(
    private val d: CoreDependencies,
    private val engine: MotionEngine,
    private val ui: MutableStateFlow<AppState>,
    private val proposals: ChatProposal,
) {
    private sealed interface TurnState

    private class Turn(
        val chat: ChatId,
        val epoch: Epoch,
        val message: String,
        val memoryOn: Boolean,
        val persona: PersonaRef,
    ) : TurnState

    private class Committing(val turn: Turn, val replyId: EntryId) : TurnState

    private val active = MutableStateFlow<TurnState?>(null)
    private var job: Job? = null

    suspend fun newChat(
        title: String = "",
        persona: PersonaRef = d.settings.state.value.activePersona,
    ): ChatId {
        interrupt(InterruptCause.MotionStopped)
        val id = ChatId(UUID.randomUUID().toString())
        d.chats.create(ChatSummary(id, title, persona.name, d.clock.now()))
        open(id)
        return id
    }

    suspend fun clearAll() {
        interrupt(InterruptCause.MotionStopped)
        d.chats.clearAll()
        ui.update { it.copy(chat = it.chat.copy(reply = ReplyState.Idle)) }
    }

    suspend fun open(id: ChatId) {
        d.chats.loadPage(id)
        if (id !in d.chats.state.value.chats) return
        interrupt(InterruptCause.MotionStopped)
        ui.update { it.copy(chat = it.chat.copy(chatId = id, reply = ReplyState.Idle)) }
        showArchive()
    }

    fun showArchive() {
        val committing = active.value as? Committing
        val completed = committing?.takeIf { slot ->
            d.chats.state.value.chats[slot.turn.chat]?.messages.orEmpty().any {
                it.id == slot.replyId
            } && active.compareAndSet(slot, null)
        }
        ui.update { s ->
            val archive = d.chats.state.value
            val chat =
                archive.chats[s.chat.chatId]
                    ?: archive.chats.values.maxByOrNull { it.summary.lastActivity }
            s.copy(
                chat =
                    s.chat.copy(
                        chatId = chat?.summary?.id,
                        title = chat?.summary?.title.orEmpty(),
                        entries = chat?.messages.orEmpty().withHistoricalSpeakers(),
                        hasOlderMessages = chat?.hasOlderMessages == true,
                        reply =
                            if (
                                completed != null &&
                                    completed.turn.chat == s.chat.chatId &&
                                    active.value == null
                            )
                                ReplyState.Idle
                            else s.chat.reply,
                        chats =
                            archive.chats.values
                                .sortedByDescending { it.summary.lastActivity }
                                .map { it.summary },
                    ),
                settings =
                    s.settings.copy(
                        memoryEntries = archive.memory[s.settings.persona.id].orEmpty().size,
                        memories = archive.memory[s.settings.persona.id].orEmpty().asReversed(),
                    ),
            )
        }
    }

    fun recordStop(end: EndedRun) {
        val chat = ui.value.chat.chatId ?: return
        val entry = ChatEntry.MotionStopped(EntryId("stop-" + end.id.value), end.at, end.lostDevice)
        d.scope.launch(d.dispatchers.io) {
            d.chats.append(chat, entry, builtInPersona.id, emptyList()) { true }
        }
    }

    fun stopEarlierThan(epoch: Epoch) {
        if ((active.value as? Turn)?.epoch?.let { it != epoch } == true)
            interrupt(InterruptCause.MotionStopped)
    }

    fun interrupt(cause: InterruptCause) {
        val old = active.value as? Turn ?: return
        if (active.compareAndSet(old, null)) {
            ui.update { it.copy(chat = it.chat.copy(reply = ReplyState.Interrupted(cause))) }
            job?.cancel()
        }
    }

    suspend fun send(expected: Epoch, text: String?, retry: Boolean = false) {
        if (
            !ui.value.firstRun.hasAcknowledgedAdultsOnly ||
                active.value != null ||
                ui.value.chat.sendBlockedBy != null ||
                expected != engine.epoch
        )
            return
        val catalog = d.models.state.value
        val model = catalog.models[catalog.chat] ?: return
        val settings = d.settings.state.value
        val persona = settings.activePersona
        val provider =
            d.speakingProvider(model, persona)
                ?: run {
                    ui.update { it.copy(backendNotice = BackendNotice.FeatureUnavailable) }
                    return
                }
        val message = messageText(text, retry) ?: return
        val chat = ui.value.chat.chatId ?: newChat(message.take(48), persona)
        d.chats.loadPage(chat)
        if (engine.epoch != expected) return
        if (!retry && !saveUserMessage(chat, message, expected)) return
        val turn = Turn(chat, expected, message, settings.values.memoryOn, persona)
        val authority = engine.state.value
        active.value = turn
        ui.update { it.copy(chat = it.chat.copy(reply = ReplyState.Streaming("", persona.name))) }
        if (engine.epoch != expected) {
            interrupt(InterruptCause.MotionStopped)
            return
        }
        val window = ConversationWindow.forModel(model)
        job =
            engine.launchWork(expected, WorkKind.Turn, d.dispatchers.io) {
                stream(turn, provider, window, model, authority)
            }
    }

    private fun messageText(text: String?, retry: Boolean): String? {
        val message =
            if (retry) {
                if (ui.value.chat.reply !is ReplyState.Interrupted) return null
                ui.value.chat.entries
                    .filterIsInstance<ChatEntry.Message>()
                    .lastOrNull { it.author == Author.You }
                    ?.text
            } else text?.trim()
        return message?.takeIf { it.isNotBlank() && it.length <= maximumMessageLength }
    }

    private suspend fun saveUserMessage(chat: ChatId, message: String, expected: Epoch): Boolean {
        val entry = ChatEntry.Message(entryId(), d.clock.now(), Author.You, message, null)
        if (
            !d.chats.append(chat, entry, builtInPersona.id, emptyList()) {
                engine.epoch == expected
            }
        )
            return false
        showArchive()
        return true
    }

    private suspend fun stream(
        turn: Turn,
        provider: ModelProvider,
        window: ConversationWindow,
        model: SavedModel,
        authority: EngineState,
    ) {
        try {
            val history = d.chats.turnHistory(turn.chat, window, turn.message)
            if (!valid(turn)) return
            val previewLock = Any()
            val buffer = StringBuilder()
            var lastUpdate = -Duration.INFINITE
            var generation = 0
            fun publish(expected: Int, text: String, admitted: () -> Boolean) {
                synchronized(previewLock) {
                    if (generation != expected) return
                    ui.update { state ->
                        if (valid(turn) && admitted())
                            state.copy(
                                chat =
                                    state.chat.copy(
                                        reply = ReplyState.Streaming(text, turn.persona.name)
                                    )
                            )
                        else state
                    }
                }
            }
            fun openPreview(admitted: () -> Boolean, reset: Boolean): (String) -> Unit {
                val expected =
                    synchronized(previewLock) {
                        if (reset) {
                            buffer.setLength(0)
                            lastUpdate = -Duration.INFINITE
                            ++generation
                        } else generation
                    }
                if (reset) publish(expected, "", admitted)
                return { delta ->
                    if (delta.isNotEmpty()) {
                        val snapshot =
                            synchronized(previewLock) {
                                if (generation != expected || !valid(turn) || !admitted()) null
                                else {
                                    buffer.append(delta)
                                    val now = d.clock.elapsed()
                                    if (now - lastUpdate >= 34.milliseconds) {
                                        lastUpdate = now
                                        buffer.toString()
                                    } else null
                                }
                            }
                        if (snapshot != null) publish(expected, snapshot, admitted)
                    }
                }
            }
            if (provider is StructuredProvider) {
                val reply =
                    proposals.complete(
                        provider,
                        model,
                        history,
                        authority,
                        turn.persona,
                        turn.memoryOn,
                        { valid(turn) },
                        ::openPreview,
                    )
                when (reply) {
                    is ProposalCompletion.Completed ->
                        if (valid(turn)) commitReply(turn, reply.entry)
                    ProposalCompletion.Refused ->
                        markInterrupted(turn, InterruptCause.ProviderRefused)
                    ProposalCompletion.Failed -> markInterrupted(turn, InterruptCause.ProviderError)
                    is ProposalCompletion.ProviderFailed ->
                        markInterrupted(turn, InterruptCause.ProviderError, reply.error)
                }
            } else
                finish(
                    turn,
                    provider.stream(history.messages, openPreview({ true }, reset = false)),
                )
        } catch (cancelled: CancellationException) {
            markInterrupted(turn, InterruptCause.MotionStopped)
            throw cancelled
        } catch (_: IOException) {
            DiagnosticReporter.record(DiagnosticKind.ProviderUnavailable)
            markInterrupted(turn, InterruptCause.ProviderError)
        } catch (_: RuntimeException) {
            // A malformed provider response must release the turn owner as well.
            DiagnosticReporter.record(DiagnosticKind.ProviderUnavailable)
            markInterrupted(turn, InterruptCause.ProviderError)
        }
    }

    private fun valid(turn: Turn) =
        active.value === turn && engine.epoch == turn.epoch && ui.value.chat.chatId == turn.chat

    private suspend fun finish(turn: Turn, result: TextResult) {
        if (!valid(turn)) {
            markInterrupted(turn, InterruptCause.MotionStopped)
            return
        }
        if (result !is TextResult.Completed || result.text.isBlank()) {
            markInterrupted(
                turn,
                InterruptCause.ProviderError,
                (result as? TextResult.Failed)?.error,
            )
            return
        }
        val reply = ChatEntry.Message(entryId(), d.clock.now(), Author.Persona, result.text, null)
        commitReply(turn, reply)
    }

    private suspend fun commitReply(turn: Turn, reply: ChatEntry.Message) {
        val worker = currentCoroutineContext().job
        val committing = Committing(turn, reply.id)
        withContext(NonCancellable) {
            var claimed = false
            val saved =
                try {
                    val memory =
                        if (turn.memoryOn && d.settings.state.value.values.memoryOn)
                            requestedMemory(turn.message)
                        else emptyList()
                    d.chats.append(
                        turn.chat,
                        reply.copy(speaker = turn.persona.name),
                        turn.persona.id,
                        memory,
                    ) {
                        if (!claimed)
                            claimed =
                                valid(turn) &&
                                    (memory.isEmpty() || d.settings.state.value.values.memoryOn) &&
                                    active.compareAndSet(turn, committing) &&
                                    engine.commitTurn(turn.epoch, worker)
                        claimed
                    }
                } catch (_: IOException) {
                    DiagnosticReporter.record(DiagnosticKind.StoreUnavailable)
                    if (
                        d.chats.state.value.chats[turn.chat]?.messages.orEmpty().none {
                            it.id == reply.id
                        }
                    ) {
                        settle(committing, ReplyState.Interrupted(InterruptCause.ProviderError))
                        markInterrupted(turn, InterruptCause.ProviderError)
                        return@withContext
                    }
                    true
                }
            if (saved) showArchive()
            val state =
                if (saved) ReplyState.Idle else ReplyState.Interrupted(InterruptCause.MotionStopped)
            settle(committing, state)
            if (!saved) markInterrupted(turn, InterruptCause.MotionStopped)
        }
    }

    private fun settle(committing: Committing, reply: ReplyState) {
        if (active.compareAndSet(committing, null)) {
            ui.update {
                if (it.chat.chatId == committing.turn.chat && active.value == null)
                    it.copy(chat = it.chat.copy(reply = reply))
                else it
            }
        }
    }

    private fun markInterrupted(
        turn: Turn,
        cause: InterruptCause,
        providerFailure: ProviderReplyFailure? = null,
    ) {
        if (active.compareAndSet(turn, null))
            ui.update { state ->
                if (state.chat.chatId != turn.chat || active.value != null) state
                else {
                    val interrupted =
                        if (engine.epoch != turn.epoch)
                            ReplyState.Interrupted(InterruptCause.MotionStopped)
                        else ReplyState.Interrupted(cause, providerFailure)
                    state.copy(chat = state.chat.copy(reply = interrupted))
                }
            }
    }

    private fun entryId() = EntryId(UUID.randomUUID().toString())
}

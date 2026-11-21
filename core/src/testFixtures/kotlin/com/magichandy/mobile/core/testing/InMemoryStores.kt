package com.magichandy.mobile.core.testing

import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.ChatSummary
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.ports.ChatArchive
import com.magichandy.mobile.core.ports.ChatStore
import com.magichandy.mobile.core.ports.ModelCatalog
import com.magichandy.mobile.core.ports.ModelStore
import com.magichandy.mobile.core.ports.PersonaChange
import com.magichandy.mobile.core.ports.PreferenceEdit
import com.magichandy.mobile.core.ports.SavedChat
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.SettingsDocument
import com.magichandy.mobile.core.ports.SettingsStore
import com.magichandy.mobile.core.ports.after
import com.magichandy.mobile.core.ports.builtInPersona
import com.magichandy.mobile.core.ports.newMemories
import com.magichandy.mobile.core.ports.withFields
import com.magichandy.mobile.core.ports.withPersonaEdit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-process transactional chat and memory store for debug builds. */
public class InMemoryChats : ChatStore {
    private val current = MutableStateFlow(ChatArchive())
    internal var savedPersonas: Set<PersonaId> = emptySet()

    internal fun forget(persona: PersonaId) {
        current.update { it.copy(memory = it.memory - persona) }
    }

    override val state: StateFlow<ChatArchive> = current.asStateFlow()

    override suspend fun create(chat: ChatSummary): Unit =
        synchronized(this) {
            current.update {
                if (chat.id in it.chats) it
                else it.copy(chats = it.chats + (chat.id to SavedChat(chat, emptyList())))
            }
        }

    override suspend fun append(
        chat: ChatId,
        entry: ChatEntry,
        persona: PersonaId,
        memories: List<String>,
        admitted: () -> Boolean,
    ): Boolean {
        synchronized(this) {
            val old = current.value
            val transcript = old.chats[chat] ?: return false
            for ((id, stored) in old.chats) {
                val duplicate = stored.messages.firstOrNull { it.id == entry.id }
                if (duplicate != null) return id == chat && duplicate == entry
            }
            val next =
                transcript.copy(
                    summary = transcript.summary.after(entry),
                    messages = transcript.messages + entry,
                )
            val previous = old.memory[persona].orEmpty()
            val added =
                if (persona == builtInPersona.id || persona in savedPersonas)
                    newMemories(previous, memories)
                else emptyList()
            val memory =
                if (added.isEmpty()) old.memory else old.memory + (persona to (previous + added))
            val result = old.copy(chats = old.chats + (chat to next), memory = memory)
            if (!admitted()) return false
            current.value = result
            return true
        }
    }

    override suspend fun clearMemory(persona: PersonaId): Unit =
        synchronized(this) {
            forget(persona)
        }

    override suspend fun clearAll(): Unit =
        synchronized(this) {
            current.value = ChatArchive()
        }
}

/** In-process named connections and role bindings for debug builds. */
public class InMemoryModels(initial: ModelCatalog = ModelCatalog()) : ModelStore {
    private val current = MutableStateFlow(initial)
    override val state: StateFlow<ModelCatalog> = current.asStateFlow()

    override suspend fun save(model: SavedModel) {
        save(model, selectForChat = false)
    }

    override suspend fun save(model: SavedModel, selectForChat: Boolean) {
        current.update {
            it.copy(
                models = it.models + (model.info.id to model),
                chat = if (selectForChat) model.info.id else it.chat ?: model.info.id,
            )
        }
    }

    override suspend fun remove(id: ConnectionId) {
        current.update {
            it.copy(
                models = it.models - id,
                chat = it.chat?.takeUnless { role -> role == id },
                planner = it.planner?.takeUnless { role -> role == id },
            )
        }
    }

    override suspend fun bindChat(id: ConnectionId) {
        current.update { if (id in it.models) it.copy(chat = id) else it }
    }

    override suspend fun bindPlanner(id: ConnectionId?) {
        current.update { if (id == null || id in it.models) it.copy(planner = id) else it }
    }
}

/** In-memory implementation of the revisioned dirty-field settings transaction for debug builds. */
public class InMemorySettings(
    initial: SettingsDocument = SettingsDocument(),
    private val chats: InMemoryChats = InMemoryChats(),
) : SettingsStore {
    init {
        chats.savedPersonas = initial.customPersonas.map { it.id }.toSet()
    }

    private val current = MutableStateFlow(initial)
    override val state: StateFlow<SettingsDocument> = current.asStateFlow()

    override suspend fun editPersonas(edit: (SettingsDocument) -> PersonaChange): FormResult =
        synchronized(chats) {
            val old = current.value
            when (val change = edit(old)) {
                is PersonaChange.Rejected -> FormResult.Rejected(change.reason)
                is PersonaChange.Edit -> {
                    val next = old.withPersonaEdit(change)
                    chats.savedPersonas = next.customPersonas.map { it.id }.toSet()
                    change.delete?.let(chats::forget)
                    current.value = next
                    FormResult.Accepted
                }
            }
        }

    override suspend fun patch(baseRevision: Long, edits: List<PreferenceEdit>): Boolean =
        synchronized(chats) {
            val old = current.value
            if (
                baseRevision !in 0..old.revision ||
                    edits.any { (old.changedAt[it.field] ?: 0) > baseRevision }
            )
                return false
            current.value = old.withFields(edits)
            true
        }
}

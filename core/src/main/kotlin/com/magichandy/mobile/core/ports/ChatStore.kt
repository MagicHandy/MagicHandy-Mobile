package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.ChatSummary
import com.magichandy.mobile.core.api.PersonaId
import kotlinx.coroutines.flow.StateFlow

/** A saved chat and its ordered transcript. */
public data class SavedChat(public val summary: ChatSummary, public val messages: List<ChatEntry>)

/** Immutable transaction view of conversations and persona-scoped memory. */
public data class ChatArchive(
    public val chats: Map<ChatId, SavedChat> = emptyMap(),
    public val memory: Map<PersonaId, List<String>> = emptyMap(),
)

/** One transaction commits a validated assistant turn together with its memory changes. */
public interface ChatStore {
    /**
     * Latest committed snapshot. The Room adapter can present a bounded page of each transcript.
     */
    public val state: StateFlow<ChatArchive>

    /** Create the chat if missing. Never overwrite a transcript that is already present. */
    public suspend fun create(chat: ChatSummary)

    /**
     * Append the entry exactly once. Recheck [admitted] inside the transaction immediately before
     * commit.
     */
    public suspend fun append(
        chat: ChatId,
        entry: ChatEntry,
        persona: PersonaId,
        memories: List<String>,
        admitted: () -> Boolean,
    ): Boolean

    /** Remove only the selected persona's memory. */
    public suspend fun clearMemory(persona: PersonaId)

    /** Delete all conversations and their memory in one transaction. */
    public suspend fun clearAll()
}

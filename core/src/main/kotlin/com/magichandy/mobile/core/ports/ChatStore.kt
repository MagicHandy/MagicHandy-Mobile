package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.ChatSummary
import com.magichandy.mobile.core.api.EntryId
import com.magichandy.mobile.core.api.PersonaId
import kotlinx.coroutines.flow.StateFlow

/** Largest model window (512 prior dialogue entries) plus the current message. */
public const val recentChatEntryLimit: Int = 513

/** Bounded input shared by the model request and its stored-message count, newest first. */
public fun List<ChatEntry>.recentModelEntries(): Sequence<ChatEntry> =
    asReversed().asSequence().take(recentChatEntryLimit)

/** A saved chat and its ordered transcript. */
public data class SavedChat(
    public val summary: ChatSummary,
    public val messages: List<ChatEntry>,
    public val hasOlderMessages: Boolean = false,
)

/** Keep the chat list's speaker current while preserving attribution on individual messages. */
public fun ChatSummary.after(entry: ChatEntry): ChatSummary =
    copy(
        title =
            if (title.isBlank() && entry is ChatEntry.Message && entry.author == Author.You)
                entry.text.trim().take(48)
            else title,
        lastActivity = entry.at,
        personaName = (entry as? ChatEntry.Message)?.speaker ?: personaName,
    )

/** Immutable transaction view of conversations and persona-scoped memory. */
public data class ChatArchive(
    public val chats: Map<ChatId, SavedChat> = emptyMap(),
    public val memory: Map<PersonaId, List<String>> = emptyMap(),
)

/** Alpha.89 admits at most 200 distinct durable facts. A duplicate consumes no additional slot. */
public fun newMemories(existing: List<String>, proposed: List<String>): List<String> {
    if (proposed.isEmpty() || existing.size >= 200) return emptyList()
    val known = existing.toMutableSet()
    return proposed
        .asSequence()
        .filter { it.isNotBlank() && known.add(it) }
        .take(200 - existing.size)
        .toList()
}

/** One transaction commits a validated assistant turn together with its memory changes. */
public interface ChatStore {
    /** Load the named chat's bounded transcript when its screen opens. */
    public suspend fun loadPage(chat: ChatId) {}

    /** Read at most [limit] entries from the beginning of this conversation, in storage order. */
    public suspend fun opening(chat: ChatId, limit: Int): List<ChatEntry> =
        state.value.chats[chat]?.messages.orEmpty().take(limit)

    /** Prepend older entries before [oldest] if the same chat and cursor are still open. */
    public suspend fun loadOlder(chat: ChatId, oldest: EntryId) {}

    /**
     * Release older cached messages under memory pressure. Durable messages and run state survive.
     */
    public suspend fun trimPages() {}

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

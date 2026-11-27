package com.magichandy.mobile.platform.storage

import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.ChatSummary
import com.magichandy.mobile.core.api.EntryId
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.ports.ChatArchive
import com.magichandy.mobile.core.ports.SavedChat
import com.magichandy.mobile.core.ports.after
import com.magichandy.mobile.core.ports.recentChatEntryLimit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

private const val OLDER_ENTRIES = 100

/** Active transcript, chat headers and memory. RoomStores serializes every read and update. */
internal class RoomChatPages(private val dao: RecordsDao, private val codec: Json) {
    private val archive = MutableStateFlow(ChatArchive())
    val state = archive.asStateFlow()
    private var page: ChatId? = null
    private var pageLimit = recentChatEntryLimit

    fun restore() {
        val headers = dao.headers()
        page = headers.firstOrNull()?.let { ChatId(it.id) }
        val chats = headers.associate { h ->
            val summary = restoreTitle(h)
            summary.id to
                if (summary.id == page) recent(summary) else SavedChat(summary, emptyList())
        }
        archive.value =
            ChatArchive(chats, dao.memories().groupBy({ PersonaId(it.persona) }, { it.text }))
    }

    /** Repair pre-0.1.9 untitled conversations using the first user entry, retaining activity. */
    private fun restoreTitle(header: Header): ChatSummary {
        val summary = codec.decodeFromString<ChatSummary>(header.payload)
        if (summary.title.isNotBlank()) return summary
        var after = 0L
        while (true) {
            val rows = dao.nextForTitle(header.id, after)
            if (rows.isEmpty()) return summary
            for (row in rows) {
                val entry = codec.decodeFromString<ChatEntry>(row.payload)
                val title = summary.after(entry).title
                if (title.isNotBlank()) {
                    val restored = summary.copy(title = title)
                    dao.update(header.copy(payload = codec.encodeToString(restored)))
                    return restored
                }
            }
            after = rows.last().sequence
        }
    }

    private fun recent(summary: ChatSummary): SavedChat {
        val rows = dao.page(summary.id.value, recentChatEntryLimit + 1)
        return SavedChat(
            summary,
            decodePage(rows, recentChatEntryLimit),
            rows.size > recentChatEntryLimit,
        )
    }

    private fun decodePage(rows: List<String>, limit: Int): List<ChatEntry> =
        rows.take(limit).asReversed().map { codec.decodeFromString<ChatEntry>(it) }

    private fun showPage(chat: SavedChat) {
        val before = archive.value
        val old = page?.takeIf { it != chat.summary.id }?.let { before.chats[it] }
        val retained =
            if (old == null) before.chats
            else before.chats + (old.summary.id to SavedChat(old.summary, emptyList()))
        page = chat.summary.id
        pageLimit = recentChatEntryLimit
        archive.value = before.copy(chats = retained + (chat.summary.id to chat))
    }

    fun publishAppend(
        summary: ChatSummary,
        entry: ChatEntry,
        persona: PersonaId,
        memories: List<String>,
    ) {
        val before = archive.value
        val old = before.chats[summary.id]?.messages.orEmpty()
        if (summary.id == page && pageLimit > recentChatEntryLimit) pageLimit++
        val visible = if (summary.id == page) (old + entry).takeLast(pageLimit) else emptyList()
        val more =
            summary.id == page &&
                (before.chats[summary.id]?.hasOlderMessages == true || old.size >= pageLimit)
        val memory =
            if (memories.isEmpty()) before.memory
            else
                before.memory +
                    (persona to (before.memory[persona].orEmpty() + memories).distinct())
        archive.value =
            before.copy(
                chats = before.chats + (summary.id to SavedChat(summary, visible, more)),
                memory = memory,
            )
    }

    fun open(chat: ChatId) {
        val summary = archive.value.chats[chat]?.summary ?: return
        if (page != chat || pageLimit < recentChatEntryLimit) showPage(recent(summary))
    }

    fun older(chat: ChatId, oldest: EntryId) {
        val before = archive.value
        val current = before.chats[chat] ?: return
        if (
            page != chat ||
                !current.hasOlderMessages ||
                current.messages.firstOrNull()?.id != oldest
        )
            return
        val rows = dao.before(chat.value, oldest.value, OLDER_ENTRIES + 1)
        val older = decodePage(rows, OLDER_ENTRIES)
        pageLimit += older.size
        archive.value =
            before.copy(
                chats =
                    before.chats +
                        (chat to
                            current.copy(
                                messages = older + current.messages,
                                hasOlderMessages = rows.size > OLDER_ENTRIES,
                            ))
            )
    }

    fun trim() {
        pageLimit = 64
        val current = archive.value
        val id = page ?: return
        val chat = current.chats[id] ?: return
        archive.value =
            current.copy(
                chats =
                    current.chats +
                        (id to
                            chat.copy(
                                messages = chat.messages.takeLast(pageLimit),
                                hasOlderMessages =
                                    chat.hasOlderMessages || chat.messages.size > pageLimit,
                            ))
            )
    }

    fun created(chat: ChatSummary) {
        val existing = archive.value.chats[chat.id]
        showPage(if (existing == null) SavedChat(chat, emptyList()) else recent(existing.summary))
    }

    fun forget(persona: PersonaId) {
        archive.value = archive.value.copy(memory = archive.value.memory - persona)
    }

    fun clear() {
        page = null
        archive.value = ChatArchive()
    }
}

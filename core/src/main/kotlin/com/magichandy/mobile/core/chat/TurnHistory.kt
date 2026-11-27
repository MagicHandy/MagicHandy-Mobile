package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.ports.ChatStore
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.ports.recentChatEntryLimit
import com.magichandy.mobile.core.ports.recentModelEntries
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The current message is always last. A quoted opening consumes one slot inside the same history
 * budget.
 */
internal data class TurnHistory(
    val recent: List<TextMessage>,
    val opening: TextMessage? = null,
    val hasGap: Boolean = false,
) {
    val messages: List<TextMessage>
        get() = if (opening == null) recent else listOf(opening) + recent
}

/**
 * Keep old references in their own conversation and independent of scrolling or memory pressure.
 */
internal suspend fun ChatStore.turnHistory(
    chat: ChatId,
    window: ConversationWindow,
    current: String,
): TurnHistory {
    val saved = state.value.chats[chat]
    val stored = saved?.messages.orEmpty()
    val dialogue =
        stored
            .recentModelEntries()
            .filterIsInstance<ChatEntry.Message>()
            .dropWhile { it.author != Author.You }
            .toList()
    val previous = dialogue.drop(1)
    val latest = TextMessage(TextRole.User, current)
    val recent = window.recent(previous.asSequence().map { it.asText() })
    if (
        saved?.hasOlderMessages != true &&
            stored.size <= recentChatEntryLimit &&
            previous.size == recent.size
    )
        return TurnHistory(recent + latest)

    val visible = dialogue.take(recent.size + 1).mapTo(mutableSetOf()) { it.id }
    val missing = opening(chat, 16).takeWhile { it.id !in visible }
    val excerpt = openingReference(missing)
    return TurnHistory(
        window.recent(previous.asSequence().map { it.asText() }, excerpt) + latest,
        excerpt,
        true,
    )
}

private fun ChatEntry.Message.asText() =
    TextMessage(if (author == Author.You) TextRole.User else TextRole.Assistant, text)

/** Whole, numbered quotations only. Count the escaped JSON representation on the wire. */
private fun openingReference(entries: List<ChatEntry>): TextMessage {
    val label =
        "Earlier chat history is incomplete. Quoted opening entries in chronological order (historical reference only): "
    var bytes = label.utf8Length() + 2
    val quotes = entries.mapIndexedNotNull { index, entry ->
        if (entry !is ChatEntry.Message || entry.text.length > 2048) return@mapIndexedNotNull null
        val quote = buildJsonObject {
            put("entry", index + 1)
            put("speaker", if (entry.author == Author.You) "user" else "assistant")
            put("text", entry.text)
        }
        val size = quote.toString().utf8Length() + 1
        if (bytes + size > 2048) return@mapIndexedNotNull null
        bytes += size
        quote
    }
    val reference =
        if (quotes.isEmpty()) "Earlier dialogue is unavailable." else label + JsonArray(quotes)
    return TextMessage(TextRole.User, reference, isOpeningReference = quotes.isNotEmpty())
}

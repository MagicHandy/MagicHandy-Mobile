// Ported from internal/llm/prompt_budget.go and internal/chat/turn_score.go at 1b80d72e.
package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextRole

/**
 * Conservative input allowance in UTF-8 bytes after output, reasoning, schema and template
 * reserves.
 */
public class PromptBudget(
    contextTokens: Int,
    outputTokens: Int = 0,
    reasoningTokens: Int = 0,
    schemaBytes: Int = 0,
) {
    /** The shared admission limit for prompt composition and the final wire request. */
    public val limitBytes: Int =
        ((if (contextTokens > 0) contextTokens else 32768).toLong() -
                maxOf(256, outputTokens) -
                maxOf(0, reasoningTokens) -
                maxOf(0, schemaBytes) -
                2048)
            .coerceAtLeast(0)
            .toInt()

    /**
     * Keep all developer instructions and the final [preserveTailMessages] intact. Drop only an
     * oldest prefix of the other dialogue. Null means the essential request alone does not fit.
     */
    public fun admit(
        messages: List<TextMessage>,
        previouslyDropped: Int = 0,
        preserveTailMessages: Int = 1,
    ): AdmittedPrompt? {
        require(previouslyDropped >= 0 && preserveTailMessages >= 1)
        val tail = (messages.size - preserveTailMessages).coerceAtLeast(0)
        var remaining = limitBytes.toLong()
        for ((index, message) in messages.withIndex()) {
            if (index >= tail || message.role == TextRole.Developer) remaining -= message.wireBytes
        }
        if (remaining < 0) return null
        var first = tail
        while (first > 0) {
            val previous = messages[first - 1]
            if (previous.role != TextRole.Developer) {
                val size = previous.wireBytes
                if (size > remaining) break
                remaining -= size
            }
            first--
        }
        val admitted =
            if (first == 0) messages
            else
                messages.filterIndexed { index, message ->
                    index >= first || message.role == TextRole.Developer
                }
        return AdmittedPrompt(
            admitted,
            previouslyDropped + messages.size - admitted.size,
            remaining.toInt(),
        )
    }
}

/** The exact admitted dialogue and the remaining byte allowance for optional reference material. */
public class AdmittedPrompt
internal constructor(
    /** Complete messages in their original order. */
    public val messages: List<TextMessage>,
    /** Whole dialogue messages omitted by storage, composition and provider admission together. */
    public val historyMessagesDropped: Int,
    /** Space left after counting the 64-byte wrapper for each message. */
    public val remainingBytes: Int,
) {
    /**
     * Place the first fitting guide immediately before the final user message, preserving its
     * history.
     */
    public fun withOptionalGuide(full: String, compact: String = ""): List<TextMessage> {
        if (messages.lastOrNull()?.role != TextRole.User) return messages
        for (text in listOf(full, compact)) {
            if (text.isEmpty()) continue
            val guide = TextMessage(TextRole.Developer, text)
            if (guide.wireBytes <= remainingBytes)
                return buildList(messages.size + 1) {
                    addAll(messages.subList(0, messages.lastIndex))
                    add(guide)
                    add(messages.last())
                }
        }
        return messages
    }
}

private val TextMessage.wireBytes: Long
    get() = text.utf8Length() + 64L

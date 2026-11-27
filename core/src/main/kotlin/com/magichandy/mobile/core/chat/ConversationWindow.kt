// Ported from internal/config/conversation_window.go and internal/chat/continuous_history.go at
// 1b80d72e.
package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.TextMessage

/** Row and UTF-8 content limits count prior dialogue. The current user turn is separate. */
internal class ConversationWindow
private constructor(
    val contextTokens: Int,
    val messages: Int,
    val historyBytes: Int,
) {
    /** Read only the suffix needed from storage, newest first. Never split a stored message. */
    fun recent(
        newestFirst: Sequence<TextMessage>,
        opening: TextMessage? = null,
    ): List<TextMessage> {
        var remaining = historyBytes.toLong() - (opening?.text?.utf8Length() ?: 0L)
        return newestFirst
            .take(messages - if (opening == null) 0 else 1)
            .takeWhile {
                remaining -= it.text.utf8Length()
                remaining >= 0
            }
            .toMutableList()
            .apply { reverse() }
    }

    companion object {
        /** Hosted OpenRouter limits can be smaller than a capability tier's normal window. */
        fun forModel(model: SavedModel): ConversationWindow {
            if (model.info.provider != ProviderKind.OpenRouter)
                return named(model.info.capability, model.contextTokens)
            val limit = model.contextTokens.takeIf { it > 0 } ?: 65536
            val window = named(model.info.capability, limit)
            return if (window.contextTokens == limit) window
            else ConversationWindow(limit, window.messages, window.historyBytes)
        }

        fun named(level: CapabilityLevel, contextTokens: Int = 0): ConversationWindow {
            val floor =
                when (level) {
                    CapabilityLevel.Simple -> 16384
                    CapabilityLevel.Intermediate -> 32768
                    CapabilityLevel.Advanced -> 65536
                }
            return when (val size = maxOf(contextTokens, floor)) {
                131072 -> ConversationWindow(size, 256, 96000)
                262144 -> ConversationWindow(size, 512, 192000)
                16384,
                32768 -> ConversationWindow(size, 64, 24000)
                else -> ConversationWindow(65536, 64, 24000)
            }
        }

        /** Desktop managed-runtime reference policy. Every mobile LAN connection uses [named]. */
        fun managed(
            level: CapabilityLevel,
            mode: MotionMode,
            contextTokens: Int,
        ): ConversationWindow {
            if (level == CapabilityLevel.Advanced) return named(level, contextTokens)
            val extended = mode == MotionMode.Layered && contextTokens >= 32768
            return ConversationWindow(contextTokens, if (extended) 96 else 64, 24000)
        }
    }
}

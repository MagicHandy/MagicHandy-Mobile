// Ported from internal/config/conversation_window.go and internal/chat/continuous_history.go at
// 1b80d72e.
package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.ports.TextMessage

/** Row and UTF-8 content limits count prior dialogue. The current user turn is separate. */
internal class ConversationWindow
private constructor(
    val contextTokens: Int,
    val messages: Int,
    val historyBytes: Int,
) {
    /** Read only the suffix needed from storage, newest first. Never split a stored message. */
    fun recent(newestFirst: Sequence<TextMessage>): List<TextMessage> {
        var remaining = historyBytes.toLong()
        return newestFirst
            .take(messages)
            .takeWhile {
                remaining -= it.text.utf8Length()
                remaining >= 0
            }
            .toMutableList()
            .apply { reverse() }
    }

    companion object {
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

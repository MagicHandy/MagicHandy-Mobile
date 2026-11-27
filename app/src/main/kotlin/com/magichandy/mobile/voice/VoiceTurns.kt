package com.magichandy.mobile.voice

import android.os.SystemClock
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.api.VoiceListening
import com.magichandy.mobile.core.api.maximumMessageLength

/**
 * Buffers transcripts for one listening epoch. The core rechecks the epoch and destination at
 * admission.
 */
internal class VoiceTurns(
    private val core: MagicHandyCore,
    private val epoch: Long,
    private val setListening: ((VoiceListening) -> VoiceListening) -> Unit,
    private val overloaded: () -> Unit,
) {
    /** Turns waiting for the streaming reply, and the chat they were heard in. */
    private val waiting = StringBuilder()
    private var waitingChat: ChatId? = null

    /** When a turn was sent, until its reply is seen streaming. */
    private var sentAt: Long? = null
    private var interruptedAtSend: ReplyState.Interrupted? = null

    fun heard(text: String, now: AppState) {
        if (core.currentStopEpoch != epoch) return
        if (isReplyBusy(now) || waiting.isNotEmpty()) {
            if (waiting.isEmpty()) waitingChat = now.chat.chatId
            if (waiting.length + text.length + 1 > maximumMessageLength) {
                overloaded()
                return
            }
            if (waiting.isNotEmpty()) waiting.append(' ')
            waiting.append(text)
            setListening { it.copy(isWaiting = true) }
            return
        }
        send(text, now)
    }

    private fun send(text: String, now: AppState) {
        val blocked = now.chat.sendBlockedBy
        if (blocked != null) {
            setListening { it.copy(cantSend = blocked, isWaiting = false) }
            return
        }
        if (core.currentStopEpoch != epoch) return
        interruptedAtSend = now.chat.reply as? ReplyState.Interrupted
        core.dispatch(
            UserIntent.SendVoiceMessage(
                text.take(maximumMessageLength),
                epoch,
                now.chat.chatId,
            )
        )
        sentAt = SystemClock.elapsedRealtime()
        setListening { it.copy(cantSend = null, isWaiting = false) }
    }

    /**
     * True while a reply streams, or a sent turn hasn't started streaming yet: the core takes one
     * turn at a time and drops a message sent during another.
     */
    private fun isReplyBusy(now: AppState): Boolean {
        if (now.chat.reply is ReplyState.Streaming) return true
        val sent = sentAt ?: return false
        return SystemClock.elapsedRealtime() - sent < SEND_GRACE_MILLIS
    }

    fun observe(now: AppState) {
        if (core.currentStopEpoch != epoch) return
        val reply = now.chat.reply
        if (reply is ReplyState.Streaming) {
            sentAt = null
            interruptedAtSend = null
        }
        if (waiting.isNotEmpty()) {
            when {
                now.chat.chatId != waitingChat ||
                    reply is ReplyState.Interrupted && reply !== interruptedAtSend -> {
                    waiting.clear()
                    setListening { it.copy(isWaiting = false) }
                }
                !isReplyBusy(now) -> flushWaiting(now)
            }
        }
        if (now.chat.sendBlockedBy == null) {
            setListening { if (it.cantSend == null) it else it.copy(cantSend = null) }
        }
    }

    private fun flushWaiting(now: AppState) {
        val text = waiting.toString()
        waiting.clear()
        waitingChat = null
        send(text, now)
    }

    private companion object {
        const val SEND_GRACE_MILLIS = 3_000L
    }
}

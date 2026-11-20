package com.magichandy.mobile.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.magichandy.mobile.core.api.ReplyState
import java.text.BreakIterator
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Reads a streaming reply to TalkBack a sentence at a time, as each sentence completes, then the
 * rest once the reply ends (plan §16). The bubble streams as usual. This node holds only the newest
 * sentences in a polite live region, so TalkBack never rereads the reply from its start, and the
 * Stop receipt, which is assertive, still comes first.
 *
 * @param finalText the persona's newest saved message, which replaces the stream when it ends.
 * @param interruption what to read if the reply is discarded, or null when something else, such as
 *   the Stop receipt, already says so.
 */
@Composable
internal fun ReplyAnnouncer(
    reply: ReplyState,
    finalText: String?,
    interruption: String?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    var read by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var spoken by remember { mutableStateOf("") }
    LaunchedEffect(reply, finalText) {
        when (reply) {
            is ReplyState.Streaming -> {
                // A stream that doesn't continue what was read is a new reply.
                if (!isStreaming || !reply.text.startsWith(read)) read = ""
                isStreaming = true
                val end = completedSentencesEnd(reply.text, read.length, locale)
                if (end != null) {
                    spoken = reply.text.substring(read.length, end).trim()
                    read = reply.text.substring(0, end)
                }
            }
            ReplyState.Idle ->
                if (isStreaming) {
                    isStreaming = false
                    val text = finalText.orEmpty()
                    spoken =
                        (if (text.startsWith(read)) text.substring(read.length) else text).trim()
                    read = ""
                }
            is ReplyState.Interrupted -> {
                if (isStreaming && interruption != null) spoken = interruption
                isStreaming = false
                read = ""
            }
        }
    }
    // Clear what was read a little later, so the node doesn't linger in TalkBack's swipe order.
    LaunchedEffect(spoken) {
        if (spoken.isNotEmpty()) {
            delay(CLEAR_AFTER)
            spoken = ""
        }
    }
    Box(
        modifier.size(1.dp).semantics {
            liveRegion = LiveRegionMode.Polite
            if (spoken.isNotEmpty()) contentDescription = spoken
        }
    )
}

/**
 * Where the sentences of [text] that are complete, and start at or after [from], end. The last
 * sentence counts as complete only once the next one has begun, because the stream may still be
 * adding to it. Null when no new sentence is complete yet.
 */
internal fun completedSentencesEnd(text: String, from: Int, locale: Locale): Int? {
    if (from >= text.length) return null
    val sentences = BreakIterator.getSentenceInstance(locale)
    sentences.setText(text)
    var end: Int? = null
    var boundary = sentences.following(from)
    while (boundary != BreakIterator.DONE && boundary < text.length) {
        end = boundary
        boundary = sentences.next()
    }
    return end
}

private val CLEAR_AFTER = 10.seconds

package com.magichandy.mobile.ui.voice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.DownloadPhase
import kotlinx.coroutines.delay

/**
 * Tells TalkBack when listening starts and ends, and about errors, and nothing else: the voice bar
 * itself isn't a live region, so the meter and "Hearing you" never interrupt (docs/voice-design.md
 * §4). A polite live region, so the Stop receipt, which is assertive, still comes first.
 */
@Composable
fun VoiceAnnouncer(voice: VoiceUi, modifier: Modifier = Modifier) {
    val state = voice.state
    val isReady = state.listening?.isReady == true
    val isListening = state.listening != null
    val notice = state.notice
    val failed = state.download?.takeIf { it.phase == DownloadPhase.Failed }?.problem
    val listeningText = stringResource(R.string.voice_announce_listening)
    val endedText = stringResource(R.string.voice_announce_ended)
    val noticeText = notice?.let { noticeText(it) }
    val failedText = failed?.let { problemText(it) }
    var spoken by remember { mutableStateOf("") }
    var wasListening by remember { mutableStateOf(isListening) }
    LaunchedEffect(isReady) { if (isReady) spoken = listeningText }
    LaunchedEffect(isListening, noticeText) {
        if (wasListening && !isListening) spoken = noticeText ?: endedText
        else if (!isListening && noticeText != null) spoken = noticeText
        wasListening = isListening
    }
    LaunchedEffect(failedText) { if (failedText != null) spoken = failedText }
    // Clear what was read a little later, so the node doesn't linger in TalkBack's swipe order.
    LaunchedEffect(spoken) {
        if (spoken.isNotEmpty()) {
            delay(CLEAR_AFTER_MILLIS)
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

private const val CLEAR_AFTER_MILLIS = 4_000L

package com.magichandy.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.InterruptCause
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.StatusLabel
import com.magichandy.mobile.ui.components.bottomHairline
import com.magichandy.mobile.ui.components.topHairline
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Chat while playing (board V-Chat): the header, the transcript and its notices, then the deck.
 * While the keyboard is open, the deck keeps only the strip and the composer (board V-Keyboard).
 */
@Composable
fun ChatScreen(
    state: AppState,
    isTyping: Boolean,
    onIntent: (UserIntent) -> Unit,
    onOpenChats: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        if (!isTyping) ChatHeader(state, onOpenChats)
        ChatBanners(
            state = state,
            onIntent = onIntent,
            onOpenDevices = onOpenDevices,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.padding(horizontal = MagicHandyTheme.dimens.m),
        )
        Transcript(state, onIntent, Modifier.weight(1f))
        ChatDeck(state = state, isTyping = isTyping, onIntent = onIntent, onOpenLive = onOpenLive)
    }
}

@Composable
private fun ChatHeader(state: AppState, onOpenChats: () -> Unit) {
    val chat = state.chat
    val colors = MagicHandyTheme.colors
    val inRun =
        state.devices.devices
            .filter { it.membership is RunMembership.InRun }
            .take(MAX_HEADER_DEVICES)
    val title = chat.title.ifBlank { stringResource(R.string.chats_new) }
    val openChats = stringResource(R.string.chat_open_chats, title)
    Row(
        Modifier.fillMaxWidth()
            .padding(
                start = MagicHandyTheme.dimens.l,
                end = MagicHandyTheme.dimens.m,
                top = MagicHandyTheme.dimens.roomy,
                bottom = MagicHandyTheme.dimens.s,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.heightIn(min = MagicHandyTheme.dimens.touch)
                .clip(MagicHandyTheme.shapes.control)
                .clickable(role = Role.Button, onClick = onOpenChats)
                .semantics(mergeDescendants = true) { contentDescription = openChats },
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MagicHandyTheme.type.title)
                Spacer(Modifier.width(MagicHandyTheme.dimens.xs))
                Icon(
                    AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = colors.muted,
                    modifier = Modifier.size(MagicHandyTheme.dimens.inlineIcon),
                )
            }
            val modelName = chat.modelName
            Text(
                if (modelName != null) {
                    stringResource(R.string.chat_subtitle, chat.personaName, modelName)
                } else {
                    stringResource(R.string.chat_subtitle_no_model, chat.personaName)
                },
                style = MagicHandyTheme.type.caption,
                color = colors.muted,
            )
        }
        Spacer(Modifier.weight(1f))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.tight),
        ) {
            for (device in inRun) {
                StatusLabel(
                    tone = Words.statusTone(device.status),
                    text =
                        device.name +
                            stringResource(R.string.separator) +
                            Words.status(device.status),
                    textStyle = MagicHandyTheme.type.caption,
                )
            }
        }
    }
}

@Composable
private fun Transcript(state: AppState, onIntent: (UserIntent) -> Unit, modifier: Modifier) {
    val chat = state.chat
    if (chat.entries.isEmpty() && chat.reply == ReplyState.Idle) {
        Box(
            modifier.fillMaxWidth().padding(MagicHandyTheme.dimens.xl),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.chat_empty, chat.personaName),
                style = MagicHandyTheme.type.body,
                color = MagicHandyTheme.colors.muted,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        modifier.fillMaxWidth(),
        reverseLayout = true,
        contentPadding =
            PaddingValues(
                horizontal = MagicHandyTheme.dimens.m,
                vertical = MagicHandyTheme.dimens.cozy,
            ),
        // Bottom-aligned, so a short transcript sits just above the deck (board V-Chat).
        verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.roomy, Alignment.Bottom),
    ) {
        when (val reply = chat.reply) {
            is ReplyState.Streaming ->
                item(key = "streaming") {
                    Bubble(
                        meta =
                            chat.personaName +
                                stringResource(R.string.separator) +
                                stringResource(R.string.time_now),
                        text = reply.text,
                        isMine = false,
                        isStreaming = true,
                    )
                }
            is ReplyState.Interrupted ->
                item(key = "interrupted") {
                    InterruptedReply(reply.cause) { onIntent(UserIntent.SendAgain) }
                }
            ReplyState.Idle -> Unit
        }
        items(chat.entries.asReversed(), key = { it.id.value }) { entry ->
            when (entry) {
                is ChatEntry.Message ->
                    Bubble(
                        meta =
                            (if (entry.author == Author.You) stringResource(R.string.author_you)
                            else chat.personaName) +
                                stringResource(R.string.separator) +
                                Words.clockTime(entry.at),
                        text = entry.text,
                        isMine = entry.author == Author.You,
                        receipt = entry.receipt?.let { Words.receipt(it) },
                    )
                is ChatEntry.MotionStopped -> MotionStoppedLine(entry)
            }
        }
    }
}

@Composable
private fun Bubble(
    meta: String,
    text: String,
    isMine: Boolean,
    receipt: String? = null,
    isStreaming: Boolean = false,
) {
    val colors = MagicHandyTheme.colors
    val shape =
        if (isMine) MagicHandyTheme.shapes.ownBubble else MagicHandyTheme.shapes.personaBubble
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxBubble = maxWidth * MagicHandyTheme.dimens.bubbleMaxFraction
        Column(
            Modifier.align(if (isMine) Alignment.CenterEnd else Alignment.CenterStart),
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
        ) {
            Text(
                meta,
                style = MagicHandyTheme.type.caption,
                color = colors.muted,
                modifier = Modifier.padding(bottom = MagicHandyTheme.dimens.xs),
            )
            Box(
                Modifier.widthIn(max = maxBubble)
                    .clip(shape)
                    .background(if (isMine) colors.ownBubble else colors.raised)
                    .border(
                        MagicHandyTheme.dimens.hairline,
                        if (isMine) colors.ownBubbleLine else colors.strongLine,
                        shape,
                    )
                    .padding(
                        horizontal = MagicHandyTheme.dimens.m,
                        vertical = MagicHandyTheme.dimens.bubbleInset,
                    )
            ) {
                if (isStreaming) StreamingText(text)
                else Text(text, style = MagicHandyTheme.type.body)
            }
            if (receipt != null) {
                Text(
                    receipt,
                    style = MagicHandyTheme.type.mono,
                    color = colors.muted,
                    modifier =
                        Modifier.padding(top = MagicHandyTheme.dimens.snug)
                            .widthIn(max = maxBubble),
                )
            }
        }
    }
}

/** The reply so far, with a caret. It isn't saved or acted on until it completes. */
@Composable
private fun StreamingText(text: String) {
    val accent = MagicHandyTheme.colors.accent
    val caret = "caret"
    Text(
        buildAnnotatedString {
            append(text)
            appendInlineContent(caret)
        },
        style = MagicHandyTheme.type.body,
        inlineContent =
            mapOf(
                caret to
                    InlineTextContent(MagicHandyTheme.type.caretSpace) {
                        Box(
                            Modifier.padding(start = MagicHandyTheme.dimens.xxs)
                                .size(
                                    MagicHandyTheme.dimens.caretWidth,
                                    MagicHandyTheme.dimens.caretHeight,
                                )
                                .background(accent)
                        )
                    }
            ),
    )
}

@Composable
private fun InterruptedReply(cause: InterruptCause, onSendAgain: () -> Unit) {
    val text =
        stringResource(
            when (cause) {
                InterruptCause.MotionStopped -> R.string.reply_interrupted_stopped
                InterruptCause.ConnectionLost -> R.string.reply_interrupted_connection
                InterruptCause.AppInBackground -> R.string.reply_interrupted_background
                InterruptCause.ProviderError -> R.string.reply_interrupted_provider
            }
        )
    Column(verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.s)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val shape = MagicHandyTheme.shapes.personaBubble
            Box(
                Modifier.widthIn(max = maxWidth * MagicHandyTheme.dimens.bubbleMaxFraction)
                    .clip(shape)
                    .background(MagicHandyTheme.colors.raised)
                    .border(MagicHandyTheme.dimens.hairline, MagicHandyTheme.colors.warn, shape)
                    .padding(
                        horizontal = MagicHandyTheme.dimens.m,
                        vertical = MagicHandyTheme.dimens.bubbleInset,
                    )
            ) {
                Text(text, style = MagicHandyTheme.type.body)
            }
        }
        AppButton(
            stringResource(R.string.send_again),
            onSendAgain,
            height = MagicHandyTheme.dimens.compactControl,
        )
    }
}

@Composable
private fun MotionStoppedLine(entry: ChatEntry.MotionStopped) {
    val time = Words.clockTime(entry.at)
    val lostDevice = entry.lostDevice
    val text =
        if (lostDevice != null) {
            stringResource(R.string.motion_stopped_entry_lost, lostDevice, time)
        } else {
            stringResource(R.string.motion_stopped_entry, time)
        }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MagicHandyTheme.type.caption,
            color = MagicHandyTheme.colors.muted,
            textAlign = TextAlign.Center,
            modifier =
                Modifier.topHairline(MagicHandyTheme.colors.line)
                    .bottomHairline(MagicHandyTheme.colors.line)
                    .padding(
                        horizontal = MagicHandyTheme.dimens.cozy,
                        vertical = MagicHandyTheme.dimens.xs,
                    ),
        )
    }
}

private const val MAX_HEADER_DEVICES = 3

package com.magichandy.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.AutopilotStatus
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.SendBlock
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.api.canSendMessage
import com.magichandy.mobile.core.api.isChatStopMessage
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.ButtonRow
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.StatusDot
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import com.magichandy.mobile.ui.voice.MicButton
import com.magichandy.mobile.ui.voice.VoiceBar
import com.magichandy.mobile.ui.voice.VoiceStrip
import com.magichandy.mobile.ui.voice.VoiceUi

/**
 * The deck (board V-Chat): the Now playing strip, the play actions while something plays, and the
 * composer. While typing it keeps only the strip and the composer (board V-Keyboard). With no chat
 * model, Choose an AI sits above the composer; the draft is kept while it's open. While voice input
 * listens, the voice bar takes the composer's place (board V-VoiceListen).
 */
@Composable
fun ChatDeck(
    state: AppState,
    isTyping: Boolean,
    onIntent: (UserIntent) -> Unit,
    onOpenLive: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseAi: () -> Unit = {},
    voice: VoiceUi = VoiceUi.None,
) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.sheet
    val isLive = state.run.phase != RunPhase.Idle || state.autopilot.status != AutopilotStatus.Off
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(MagicHandyTheme.dimens.hairline, colors.strongLine, shape)
            .padding(horizontal = MagicHandyTheme.dimens.m, vertical = MagicHandyTheme.dimens.cozy),
        verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.s),
    ) {
        NowPlayingStrip(state, onOpenLive)
        if (isLive && !isTyping) DeckActions(state, onIntent)
        if (state.chat.sendBlockedBy == SendBlock.NoChatModel && !isTyping) {
            AppButton(
                stringResource(
                    if (state.models.chatNeedsSignIn) R.string.connection_needs_sign_in
                    else R.string.ready_choose_ai
                ),
                onChooseAi,
                modifier = Modifier.fillMaxWidth(),
                height = MagicHandyTheme.dimens.compactControl,
                hasChevron = true,
            )
        }
        VoiceStrip(voice)
        if (voice.state.listening != null) VoiceBar(voice) else Composer(state, onIntent, voice)
    }
}

/** One line that says what the app is commanding. Tapping it opens Live. */
@Composable
fun NowPlayingStrip(state: AppState, onOpenLive: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.control
    val content = stripContent(state)
    val autopilot = state.autopilot
    val countdown =
        autopilot.nextChangeIn
            ?.takeIf { autopilot.status == AutopilotStatus.Active }
            ?.let(Words::countdown)
    val description =
        if (countdown != null) {
            stringResource(R.string.strip_next_change, content.text.text, countdown)
        } else {
            content.text.text
        }
    val openLive = stringResource(R.string.strip_open_live, description)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = MagicHandyTheme.dimens.control)
            .clip(shape)
            .background(colors.well)
            .border(MagicHandyTheme.dimens.hairline, colors.line, shape)
            .clickable(role = Role.Button, onClick = onOpenLive)
            .semantics { contentDescription = openLive }
            .padding(start = MagicHandyTheme.dimens.m, end = MagicHandyTheme.dimens.snug),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The readout takes the free width, so the chevron sits at the far right (board V-Chat).
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(content.tone)
            Spacer(Modifier.width(MagicHandyTheme.dimens.s))
            Text(
                content.text,
                style =
                    MagicHandyTheme.type.small.copy(fontSize = MagicHandyTheme.type.label.fontSize),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (countdown != null) {
                Box(
                    Modifier.padding(horizontal = MagicHandyTheme.dimens.s)
                        .size(
                            width = MagicHandyTheme.dimens.hairline,
                            height = MagicHandyTheme.dimens.dividerHeight,
                        )
                        .background(colors.faint)
                )
                StatusDot(DotTone.Accent)
                Spacer(Modifier.width(MagicHandyTheme.dimens.snug))
                Text(
                    countdown,
                    style =
                        MagicHandyTheme.type.mono.copy(
                            fontSize = MagicHandyTheme.type.label.fontSize
                        ),
                )
            }
        }
        Icon(
            AppIcons.ChevronUp,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(MagicHandyTheme.dimens.actionIcon),
        )
    }
}

private data class StripContent(val tone: DotTone, val text: AnnotatedString)

@Composable
private fun stripContent(state: AppState): StripContent {
    val run = state.run
    val colors = MagicHandyTheme.colors
    val mono = MagicHandyTheme.type.mono.fontFamily
    if (run.isPlanningHeld) {
        val held =
            if (run.phase == RunPhase.Playing) R.string.strip_held_playing
            else R.string.strip_held_stopped
        return StripContent(DotTone.Warn, AnnotatedString(stringResource(held)))
    }
    return when (run.phase) {
        RunPhase.Playing -> {
            val pace = run.commandedPace?.let { Words.percent(it) } ?: "–"
            val full = stringResource(R.string.strip_playing, pace)
            val start = full.indexOf(pace)
            StripContent(
                DotTone.Ok,
                buildAnnotatedString {
                    append(full)
                    if (start >= 0) {
                        addStyle(
                            SpanStyle(fontFamily = mono, fontWeight = FontWeight.SemiBold),
                            start,
                            start + pace.length,
                        )
                    }
                },
            )
        }
        RunPhase.Paused ->
            StripContent(DotTone.Idle, AnnotatedString(stringResource(R.string.strip_paused)))
        RunPhase.Edging ->
            StripContent(DotTone.Accent, AnnotatedString(stringResource(R.string.strip_edging)))
        RunPhase.Idle ->
            if (state.stopReceipt != null) {
                val stopped = stringResource(R.string.strip_stopped)
                val detail =
                    if (state.autopilot.status == AutopilotStatus.Off) {
                        stringResource(R.string.separator) +
                            stringResource(R.string.strip_autopilot_off)
                    } else {
                        ""
                    }
                StripContent(
                    DotTone.Idle,
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(stopped) }
                        withStyle(SpanStyle(color = colors.muted)) { append(detail) }
                    },
                )
            } else {
                StripContent(DotTone.Faint, AnnotatedString(stringResource(R.string.strip_idle)))
            }
    }
}

/** Autopilot, Pause and Edge in one row; with large text they stack rather than break a word. */
@Composable
private fun DeckActions(state: AppState, onIntent: (UserIntent) -> Unit) {
    val autopilot = state.autopilot.status
    val phase = state.run.phase
    ButtonRow(
        weights = listOf(1.4f, 1f, 1f),
        modifier = Modifier.fillMaxWidth(),
        gap = MagicHandyTheme.dimens.snug,
    ) {
        AppButton(
            text = stringResource(R.string.deck_autopilot),
            onClick = {
                onIntent(
                    when (autopilot) {
                        AutopilotStatus.Off -> UserIntent.StartAutopilot
                        AutopilotStatus.Paused -> UserIntent.ResumeAutopilot
                        AutopilotStatus.Active,
                        AutopilotStatus.Held -> UserIntent.TurnOffAutopilot
                    }
                )
            },
            kind =
                if (autopilot == AutopilotStatus.Off) ButtonKind.Outline else ButtonKind.Selected,
            leading =
                if (autopilot == AutopilotStatus.Active) {
                    { StatusDot(DotTone.Accent) }
                } else {
                    null
                },
            modifier = Modifier.semantics { selected = autopilot != AutopilotStatus.Off },
        )
        val isPaused = phase == RunPhase.Paused
        AppButton(
            text = stringResource(if (isPaused) R.string.deck_resume else R.string.deck_pause),
            onClick = {
                onIntent(if (isPaused) UserIntent.ResumeMotion else UserIntent.PauseMotion)
            },
            isEnabled = phase != RunPhase.Idle,
        )
        val isEdging = phase == RunPhase.Edging
        AppButton(
            text = stringResource(R.string.deck_edge),
            onClick = { onIntent(UserIntent.SetEdge(!isEdging)) },
            kind = if (isEdging) ButtonKind.Selected else ButtonKind.Outline,
            isEnabled = phase == RunPhase.Playing || isEdging,
        )
    }
}

@Composable
private fun Composer(state: AppState, onIntent: (UserIntent) -> Unit, voice: VoiceUi) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.card
    var draft by rememberSaveable { mutableStateOf("") }
    val blocked = state.chat.sendBlockedBy
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var previousEntry by rememberSaveable { mutableStateOf<String?>(null) }
    val latestUser =
        state.chat.entries.filterIsInstance<ChatEntry.Message>().lastOrNull {
            it.author == Author.You
        }
    LaunchedEffect(latestUser?.id, submitted) {
        if (
            submitted != null &&
                latestUser?.id?.value != previousEntry &&
                latestUser?.text == submitted
        ) {
            if (draft.trim() == submitted) draft = ""
            submitted = null
        }
    }
    val canSend = state.chat.canSendMessage(draft.trim())
    val send = {
        if (canSend) {
            val text = draft.trim()
            if (isChatStopMessage(text)) draft = ""
            else {
                previousEntry = latestUser?.id?.value
                submitted = text
            }
            onIntent(UserIntent.SendMessage(text))
        }
    }
    val placeholder =
        stringResource(
            when (blocked) {
                null -> R.string.composer_hint
                SendBlock.NoChatModel ->
                    if (state.models.chatNeedsSignIn) R.string.connection_needs_sign_in
                    else R.string.composer_no_model
                SendBlock.ModelUnreachable -> R.string.composer_unreachable
            }
        )
    val label = stringResource(R.string.composer_hint)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(colors.well)
            .border(MagicHandyTheme.dimens.hairline, colors.fieldLine, shape)
            .padding(start = MagicHandyTheme.dimens.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            textStyle = MagicHandyTheme.type.composer.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            maxLines = 4,
            modifier =
                Modifier.weight(1f).heightIn(min = MagicHandyTheme.dimens.touch).semantics {
                    contentDescription = label
                },
            decorationBox = { inner ->
                Box(
                    Modifier.padding(vertical = MagicHandyTheme.dimens.composerInset),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            placeholder,
                            style = MagicHandyTheme.type.composer,
                            color = colors.faint,
                        )
                    }
                    inner()
                }
            },
        )
        // With nothing typed, the microphone stands in for Send (board V-VoiceIdle).
        if (draft.isEmpty() && voice.isOffered) {
            MicButton(voice, isChatReady = blocked != SendBlock.NoChatModel)
            return@Row
        }
        val sendLabel = stringResource(R.string.composer_send)
        // Accent while chat is available, as on the board; it acts only when there is text. The
        // 48 dp target keeps a thin margin of the field around the 44 dp button.
        Box(
            Modifier.size(MagicHandyTheme.dimens.touch)
                .clip(MagicHandyTheme.shapes.control)
                .clickable(enabled = canSend, role = Role.Button, onClick = send)
                .semantics { contentDescription = sendLabel }
                .padding(MagicHandyTheme.dimens.xxs)
                .clip(MagicHandyTheme.shapes.control)
                .background(if (blocked == null) colors.accent else colors.raised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Send,
                contentDescription = null,
                tint = if (blocked == null) colors.onAccent else colors.faint,
                modifier = Modifier.size(MagicHandyTheme.dimens.actionIcon),
            )
        }
    }
}

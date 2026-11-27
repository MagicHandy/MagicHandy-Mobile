package com.magichandy.mobile.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.VoiceModel
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.RadioMark
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * The first tap on the microphone (boards V-VoiceSetup and V-VoiceModelB): what voice does, that it
 * stays on the phone, that "Stop" works, and the download. Download and start gets Compact; Choose
 * another model opens the picker, and only phones with the memory for full precision see it.
 */
@Composable
fun ColumnScope.VoiceSetupPage(voice: VoiceUi, onClose: () -> Unit) {
    val dimens = MagicHandyTheme.dimens
    val colors = MagicHandyTheme.colors
    Column(
        Modifier.padding(vertical = dimens.s),
        verticalArrangement = Arrangement.spacedBy(dimens.m),
    ) {
        Point(
            AppIcons.Mic,
            colors.accent,
            R.string.voice_setup_how,
            R.string.voice_setup_how_detail,
        )
        Point(
            AppIcons.Lock,
            colors.accent,
            R.string.voice_setup_private,
            R.string.voice_setup_private_detail,
        )
        Point(
            AppIcons.Stop,
            colors.stop,
            R.string.voice_setup_stop,
            R.string.voice_setup_stop_detail,
        )
        Point(
            AppIcons.Download,
            colors.accent,
            stringResource(
                R.string.voice_setup_download,
                fileSize(voice.downloadBytes(VoiceModel.Compact)),
            ),
            stringResource(R.string.voice_setup_download_detail),
        )
    }
    AppButton(
        stringResource(R.string.voice_download_and_start),
        {
            onClose()
            voice.withMicrophone { voice.controls.download(VoiceModel.Compact, andStart = true) }
        },
        kind = ButtonKind.Primary,
        modifier = Modifier.fillMaxWidth().padding(top = dimens.s),
    )
    if (voice.state.canChooseModel) {
        LinkButton(stringResource(R.string.voice_choose_another), voice.openPicker)
    }
    AppButton(
        stringResource(R.string.voice_not_now),
        onClose,
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = if (voice.state.canChooseModel) dimens.xxs else dimens.s),
    )
    if (!isMicrophoneAllowed(LocalContext.current)) {
        Text(
            stringResource(R.string.voice_setup_microphone),
            style = MagicHandyTheme.type.caption,
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = dimens.s),
        )
    }
}

/** A text button in the accent, for the step most people skip (board V-VoiceModelB). */
@Composable
private fun LinkButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = MagicHandyTheme.dimens.touch)
            .clip(MagicHandyTheme.shapes.control)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MagicHandyTheme.type.label, color = MagicHandyTheme.colors.accent)
    }
}

@Composable
private fun Point(icon: ImageVector, tint: Color, title: Int, detail: Int) =
    Point(icon, tint, stringResource(title), stringResource(detail))

/** One point of the sheet: its lead in bold, and the rest of the paragraph after it. */
@Composable
private fun Point(icon: ImageVector, tint: Color, title: String, detail: String) {
    val colors = MagicHandyTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.m)) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier =
                Modifier.padding(top = MagicHandyTheme.dimens.xxs)
                    .size(MagicHandyTheme.dimens.actionIcon),
        )
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.text, fontWeight = FontWeight.SemiBold)) {
                    append(title)
                }
                append(" ")
                append(detail)
            },
            style = MagicHandyTheme.type.label.copy(fontWeight = FontWeight.Normal),
            color = colors.textSecondary,
        )
    }
}

/** A small outlined word beside a title, such as Recommended or In use. */
@Composable
internal fun Tag(text: String, color: Color, line: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MagicHandyTheme.type.overline.copy(letterSpacing = TextUnit.Unspecified),
        color = color,
        modifier =
            modifier
                .border(MagicHandyTheme.dimens.hairline, line, MagicHandyTheme.shapes.small)
                .padding(horizontal = MagicHandyTheme.dimens.snug, vertical = 1.dp),
    )
}

/**
 * The speech model picker. On first use (board V-VoiceModelPick) Compact is recommended and the
 * button downloads the choice and starts listening. From Settings › Voice (board
 * V-VoiceModelSwitch) the model in use is marked, and the button downloads the other one: voice
 * keeps the old model until the new one is ready.
 */
@Composable
fun ColumnScope.VoicePickerPage(
    voice: VoiceUi,
    isFromSettings: Boolean,
    onClose: () -> Unit,
    initial: VoiceModel = if (isFromSettings) voice.state.chosen else VoiceModel.Compact,
) {
    val dimens = MagicHandyTheme.dimens
    val installed = voice.state.installed
    var selected by rememberSaveable { mutableStateOf(initial) }
    Column(
        Modifier.selectableGroup().padding(vertical = dimens.s),
        verticalArrangement = Arrangement.spacedBy(dimens.s),
    ) {
        VoiceModel.entries.forEach { model ->
            ModelCard(
                voice = voice,
                model = model,
                tag =
                    when {
                        model == installed -> stringResource(R.string.voice_in_use)
                        !isFromSettings && model == VoiceModel.Compact ->
                            stringResource(R.string.voice_recommended)
                        else -> null
                    },
                isInstalled = model == installed,
                isSelected = model == selected,
                onSelect = { selected = model },
            )
        }
    }
    val size = fileSize(voice.downloadBytes(selected))
    if (isFromSettings) {
        if (selected != installed) {
            Text(
                stringResource(
                    R.string.voice_switch_note,
                    fileSize(voice.neededBytes(selected)),
                    modelName(installed ?: VoiceModel.Compact),
                ),
                style = MagicHandyTheme.type.small,
                color = MagicHandyTheme.colors.textSecondary,
            )
            AppButton(
                stringResource(R.string.voice_download_size, size),
                {
                    onClose()
                    voice.controls.download(selected, andStart = false)
                },
                kind = ButtonKind.Primary,
                modifier = Modifier.fillMaxWidth().padding(top = dimens.m),
            )
        } else if (voice.state.chosen != installed) {
            // Back to the model in use: the switch's download stops and goes.
            AppButton(
                stringResource(R.string.action_done),
                {
                    onClose()
                    voice.controls.download(selected, andStart = false)
                },
                kind = ButtonKind.Primary,
                modifier = Modifier.fillMaxWidth().padding(top = dimens.m),
            )
        }
    } else {
        Text(
            stringResource(R.string.voice_pick_note),
            style = MagicHandyTheme.type.small,
            color = MagicHandyTheme.colors.textSecondary,
        )
        AppButton(
            stringResource(R.string.voice_download_size_and_start, size),
            {
                onClose()
                voice.withMicrophone { voice.controls.download(selected, andStart = true) }
            },
            kind = ButtonKind.Primary,
            modifier = Modifier.fillMaxWidth().padding(top = dimens.m),
        )
    }
}

@Composable
private fun ModelCard(
    voice: VoiceUi,
    model: VoiceModel,
    tag: String?,
    isInstalled: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val shape = MagicHandyTheme.shapes.card
    val size = fileSize(voice.downloadBytes(model))
    val detail =
        when (model) {
            VoiceModel.Compact ->
                stringResource(
                    if (isInstalled) R.string.voice_compact_detail_installed
                    else R.string.voice_compact_detail,
                    size,
                )
            VoiceModel.Full ->
                stringResource(
                    if (isInstalled) R.string.voice_full_detail_installed
                    else R.string.voice_full_detail,
                    size,
                )
        }
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (isSelected) colors.accentContainer else Color.Transparent)
            .border(dimens.hairline, if (isSelected) colors.accent else colors.strongLine, shape)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect)
            .padding(dimens.m),
        horizontalArrangement = Arrangement.spacedBy(dimens.m),
    ) {
        RadioMark(isSelected)
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(dimens.xxs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(dimens.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modelName(model),
                    style = MagicHandyTheme.type.body,
                    fontWeight = FontWeight.SemiBold,
                )
                if (tag != null) Tag(tag, colors.accent, colors.accent.copy(alpha = TAG_LINE))
            }
            Text(detail, style = MagicHandyTheme.type.small, color = colors.textSecondary)
        }
    }
}

private const val TAG_LINE = 0.5f

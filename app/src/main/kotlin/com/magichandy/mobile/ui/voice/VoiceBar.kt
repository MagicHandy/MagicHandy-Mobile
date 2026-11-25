package com.magichandy.mobile.ui.voice

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.SendBlock
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.IconAction
import com.magichandy.mobile.ui.components.StatusDot
import com.magichandy.mobile.ui.components.topHairline
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import com.magichandy.mobile.voice.DownloadPhase
import com.magichandy.mobile.voice.VoiceListening
import kotlin.math.roundToInt

/**
 * The voice bar (board V-VoiceListen), in place of the composer while listening: what voice is
 * doing, the meter and End. It isn't a live region: TalkBack hears only start, end and errors
 * ([VoiceAnnouncer]). With large text the meter gives way to the words, and with animations off a
 * still dot stands for it.
 */
@Composable
fun VoiceBar(voice: VoiceUi, modifier: Modifier = Modifier) {
    val listening = voice.state.listening ?: return
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val shape = MagicHandyTheme.shapes.card
    val meter by voice.meter.collectAsStateWithLifecycle()
    val label = barLabel(listening, meter.isHearing)
    val description = stringResource(R.string.voice_bar, label)
    val showsMeter = LocalDensity.current.fontScale < LARGE_TEXT && !isMotionReduced()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dimens.xs)) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = dimens.touch)
                .clip(shape)
                .background(colors.well)
                .border(dimens.hairline, colors.ownBubbleLine, shape)
                .padding(start = dimens.m, end = dimens.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.cozy),
        ) {
            Row(
                (if (showsMeter) Modifier else Modifier.weight(1f)).semantics(
                    mergeDescendants = true
                ) {
                    contentDescription = description
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.s),
            ) {
                StatusDot(if (listening.isReady) DotTone.Accent else DotTone.Faint)
                Text(label, style = MagicHandyTheme.type.label, color = colors.text)
            }
            if (showsMeter) Meter(meter.levels, Modifier.weight(1f).height(METER_HEIGHT))
            AppButton(stringResource(R.string.voice_end), voice.controls::end, height = END_HEIGHT)
        }
        val readout = meter.readout
        if (voice.state.config.showLevels && readout != null) {
            Text(readout, style = MagicHandyTheme.type.mono, color = colors.muted)
        }
    }
}

@Composable
private fun barLabel(listening: VoiceListening, isHearing: Boolean): String =
    stringResource(
        when {
            !listening.isReady -> R.string.voice_getting_ready
            listening.cantSend == SendBlock.NoChatModel -> R.string.voice_cant_send_no_model
            listening.cantSend == SendBlock.ModelUnreachable -> R.string.voice_cant_send_unreachable
            listening.isWaiting -> R.string.voice_waiting
            isHearing -> R.string.voice_hearing
            else -> R.string.voice_listening
        }
    )

/** The latest chunks' loudness as bars, newest on the right; loud ones in the accent. */
@Composable
private fun Meter(levels: List<Float>, modifier: Modifier = Modifier) {
    val quiet = MagicHandyTheme.colors.strongLine
    val hot = MagicHandyTheme.colors.accent
    Canvas(modifier) {
        val gap = 3.dp.toPx()
        val width = (size.width - gap * (METER_BARS - 1)) / METER_BARS
        if (width <= 0f) return@Canvas
        val shown =
            List(METER_BARS - levels.size.coerceAtMost(METER_BARS)) { 0f } +
                levels.takeLast(METER_BARS)
        shown.forEachIndexed { index, level ->
            val height = (size.height * level).coerceIn(MIN_BAR.toPx(), size.height)
            drawRoundRect(
                color = if (level >= HOT_LEVEL) hot else quiet,
                topLeft = Offset(index * (width + gap), (size.height - height) / 2),
                size = Size(width, height),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

/**
 * Above the composer when voice needs a word: the first download's progress, why it failed, or why
 * listening ended, each with its next step.
 */
@Composable
fun VoiceStrip(voice: VoiceUi, modifier: Modifier = Modifier) {
    val state = voice.state
    if (state.listening != null) return
    val notice = state.notice
    val download = state.download?.takeIf { state.installed == null }
    when {
        notice != null -> {
            val step = noticeStep(notice)
            StripCard(modifier) {
                Text(
                    noticeText(notice),
                    style = MagicHandyTheme.type.small,
                    color = MagicHandyTheme.colors.text,
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    stepLabel(step),
                    { voice.take(step, notice) },
                    height = MagicHandyTheme.dimens.compactControl,
                )
                IconAction(
                    AppIcons.Close,
                    stringResource(R.string.action_close),
                    voice.controls::dismissNotice,
                )
            }
        }
        download != null -> {
            val percent = (download.fraction * PERCENT).roundToInt()
            StripCard(modifier) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.xs),
                ) {
                    val problem = download.problem
                    Text(
                        when {
                            download.phase == DownloadPhase.Failed && problem != null ->
                                problemText(problem)
                            download.phase == DownloadPhase.Paused ->
                                stringResource(R.string.voice_download_paused, percent)
                            else -> stringResource(R.string.voice_downloading, percent)
                        },
                        style = MagicHandyTheme.type.small,
                        color = MagicHandyTheme.colors.text,
                    )
                    if (download.phase != DownloadPhase.Failed) {
                        LinearProgressIndicator(
                            progress = { download.fraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = MagicHandyTheme.colors.accent,
                            trackColor = MagicHandyTheme.colors.strongLine,
                            drawStopIndicator = {},
                        )
                    }
                }
                if (download.phase != DownloadPhase.Running) {
                    AppButton(
                        stringResource(
                            if (download.phase == DownloadPhase.Failed) R.string.voice_retry
                            else R.string.voice_resume
                        ),
                        voice.controls::retryDownload,
                        height = MagicHandyTheme.dimens.compactControl,
                    )
                }
                AppButton(
                    stringResource(R.string.action_cancel),
                    voice.controls::cancelDownload,
                    height = MagicHandyTheme.dimens.compactControl,
                )
            }
        }
    }
}

@Composable
private fun StripCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val shape = MagicHandyTheme.shapes.card
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MagicHandyTheme.colors.well)
            .border(MagicHandyTheme.dimens.hairline, MagicHandyTheme.colors.line, shape)
            .padding(start = MagicHandyTheme.dimens.m, end = MagicHandyTheme.dimens.xxs)
            .heightIn(min = MagicHandyTheme.dimens.touch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.s),
        content = content,
    )
}

/**
 * The composer's microphone (board V-VoiceIdle), in Send's place while nothing is typed. The first
 * tap opens the setup sheet; after that it starts listening. It's off while chat can't send and
 * while the first download runs.
 */
@Composable
fun MicButton(voice: VoiceUi, isChatReady: Boolean) {
    val colors = MagicHandyTheme.colors
    val state = voice.state
    val isEnabled = isChatReady && !(state.installed == null && state.download != null)
    val label = stringResource(R.string.voice_start)
    Box(
        Modifier.size(MagicHandyTheme.dimens.touch)
            .clip(MagicHandyTheme.shapes.control)
            .clickable(enabled = isEnabled, role = Role.Button) {
                if (state.installed == null) voice.openSetup()
                else voice.withMicrophone(voice.controls::start)
            }
            .semantics { contentDescription = label }
            .padding(MagicHandyTheme.dimens.xxs)
            .clip(MagicHandyTheme.shapes.control)
            .background(if (isEnabled) colors.accent else colors.raised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.Mic,
            contentDescription = null,
            tint = if (isEnabled) colors.onAccent else colors.faint,
            modifier = Modifier.size(MagicHandyTheme.dimens.actionIcon),
        )
    }
}

/**
 * Listening · End, above the tabs, or above Stop where there are none, on every screen but Chat
 * (board V-VoiceSettings).
 */
@Composable
fun ListeningChip(voice: VoiceUi, modifier: Modifier = Modifier) {
    val listening = voice.state.listening ?: return
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val shape = MagicHandyTheme.shapes.card
    val label =
        stringResource(
            if (listening.isReady) R.string.voice_listening else R.string.voice_getting_ready
        )
    val description = stringResource(R.string.voice_bar, label)
    Box(
        modifier
            .fillMaxWidth()
            .background(colors.chrome)
            .topHairline(colors.line)
            .padding(start = dimens.m, end = dimens.m, top = dimens.cozy)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = dimens.touch)
                .clip(shape)
                .background(colors.well)
                .border(dimens.hairline, colors.ownBubbleLine, shape)
                .padding(start = dimens.m, end = dimens.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.s),
        ) {
            Row(
                Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription = description
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.s),
            ) {
                StatusDot(if (listening.isReady) DotTone.Accent else DotTone.Faint)
                Text(label, style = MagicHandyTheme.type.label)
            }
            AppButton(stringResource(R.string.voice_end), voice.controls::end, height = END_HEIGHT)
        }
    }
}

/** True when the phone's animations are off, as Remove animations sets. */
@Composable
private fun isMotionReduced(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

private const val METER_BARS = 18
private const val HOT_LEVEL = 0.35f
private const val PERCENT = 100
private const val LARGE_TEXT = 1.3f
private val METER_HEIGHT = 26.dp

/** End fills the bar's height, inside its 2 dp inset, as on the boards. */
private val END_HEIGHT = 44.dp
private val MIN_BAR = 3.dp

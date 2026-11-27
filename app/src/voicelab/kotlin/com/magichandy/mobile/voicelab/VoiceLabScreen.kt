package com.magichandy.mobile.voicelab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.magichandy.mobile.core.api.Detector
import com.magichandy.mobile.core.api.MicSource
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.Banner
import com.magichandy.mobile.ui.components.BannerTone
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.ButtonRow
import com.magichandy.mobile.ui.components.Caption
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.Segment
import com.magichandy.mobile.ui.components.Segmented
import com.magichandy.mobile.ui.components.StatusDot
import com.magichandy.mobile.ui.components.TextInput
import com.magichandy.mobile.ui.components.ToggleRow
import com.magichandy.mobile.ui.components.ValueMenu
import com.magichandy.mobile.ui.components.segmentedWidthFor
import com.magichandy.mobile.ui.devices.CheckBox
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The voice test page (boards V-VoiceLab, V-VoiceLabListen and V-VoiceLabClip), in test builds
 * only. Its words are English: it's a measuring tool, not part of the app people use.
 */
@Composable
fun VoiceLabScreen(state: LabState, actions: LabActions, modifier: Modifier = Modifier) {
    val dimens = MagicHandyTheme.dimens
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(start = dimens.m, end = dimens.m, top = dimens.xs, bottom = dimens.l),
        verticalArrangement = Arrangement.spacedBy(dimens.cozy),
    ) {
        item { Header(state.vitals) }
        state.notice?.let { notice ->
            item {
                Banner(
                    BannerTone.Warn,
                    title = notice,
                    body = null,
                    actionLabel = "Dismiss",
                    onAction = actions::dismissNotice,
                )
            }
        }
        state.busy?.let { busy -> item { Hint(busy) } }
        item { Engines(state, actions) }
        item { Microphone(state, actions) }
        item {
            Segmented(
                segments =
                    listOf(
                        Segment(LabMode.HandsFree, "Hands-free"),
                        Segment(LabMode.Clip, "One clip, every engine"),
                    ),
                selected = state.mode,
                onSelect = actions::setMode,
            )
        }
        item {
            when (state.mode) {
                LabMode.HandsFree -> HandsFreePanel(state, actions)
                LabMode.Clip -> ClipPanel(state, actions)
            }
        }
        item { ResultsHeading(state, actions) }
        items(state.entries, key = { it.id }) { entry ->
            when (entry) {
                is Turn -> TurnCard(entry)
                is LabNote -> NoteLine(entry)
            }
        }
    }
}

@Composable
private fun Header(vitals: Vitals) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    Column(verticalArrangement = Arrangement.spacedBy(dimens.xs)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = dimens.touch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Voice test",
                style = MagicHandyTheme.type.pageTitle,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Text(
                "TEST BUILD",
                style = MagicHandyTheme.type.overline,
                color = colors.warn,
                modifier =
                    Modifier.border(dimens.hairline, colors.warnLine, MagicHandyTheme.shapes.small)
                        .padding(horizontal = dimens.snug, vertical = dimens.tight),
            )
        }
        Hint("Measures speech recognition only. Nothing here sends a message or moves a device.")
        val battery =
            vitals.battery?.let { now ->
                val change =
                    vitals.batteryAtStart?.let { start ->
                        " · " + signed(now - start) + "% in " + (vitals.minutes ?: 0) + " min"
                    } ?: ""
                "$now%$change"
            } ?: "…"
        Text(
            "Battery $battery   Temperature ${vitals.thermal}   Memory " +
                (vitals.memoryMb?.let { "%.1f GB".format(Locale.ROOT, it / MB_PER_GB) } ?: "…"),
            style = MagicHandyTheme.type.caption,
            color = colors.muted,
        )
    }
}

@Composable
private fun Engines(state: LabState, actions: LabActions) {
    Column(verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.snug)) {
        Caption("Speech engines")
        Card {
            ParakeetBuild.entries.forEachIndexed { index, build ->
                val fetch = state.builds[build] ?: Fetch.Missing
                EngineRow(
                    title = build.title,
                    note =
                        build.note +
                            " · " +
                            size(build.bytes) +
                            ((fetch as? Fetch.Failed)?.let { "\n" + it.reason } ?: ""),
                    isChecked = build in state.checkedBuilds && fetch == Fetch.Ready,
                    canCheck = fetch == Fetch.Ready,
                    onToggle = { actions.toggle(build) },
                    isFirst = index == 0,
                ) {
                    BuildStatus(build, fetch, actions)
                }
            }
            KitMode.entries.forEach { mode ->
                val status = state.kits[mode] ?: KitStatus.Checking
                EngineRow(
                    title = mode.title,
                    note =
                        mode.note + ((status as? KitStatus.Failed)?.let { "\n" + it.reason } ?: ""),
                    isChecked = mode in state.checkedKits && status == KitStatus.Ready,
                    canCheck = status == KitStatus.Ready,
                    onToggle = { actions.toggle(mode) },
                    isFirst = false,
                ) {
                    KitStatusView(mode, status, actions)
                }
            }
            CardRow(isFirst = false) {
                Text(
                    "ML Kit language",
                    style = MagicHandyTheme.type.body,
                    modifier = Modifier.weight(1f),
                )
                ValueMenu(
                    options = KitLanguage.entries,
                    selected = state.language,
                    label = { it.title },
                    onSelect = actions::setLanguage,
                )
            }
        }
    }
}

@Composable
private fun EngineRow(
    title: String,
    note: String,
    isChecked: Boolean,
    canCheck: Boolean,
    onToggle: () -> Unit,
    isFirst: Boolean,
    status: @Composable () -> Unit,
) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    CardRow(isFirst = isFirst, minHeight = dimens.listRow, verticalPadding = dimens.s) {
        Row(
            Modifier.weight(1f)
                .toggleable(
                    value = isChecked,
                    enabled = canCheck,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.m),
        ) {
            Box(Modifier.padding(vertical = dimens.xxs)) { CheckBox(isChecked) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MagicHandyTheme.type.label)
                Text(note, style = MagicHandyTheme.type.caption, color = colors.muted)
            }
        }
        Column(horizontalAlignment = Alignment.End) { status() }
    }
}

@Composable
private fun BuildStatus(build: ParakeetBuild, fetch: Fetch, actions: LabActions) {
    val colors = MagicHandyTheme.colors
    when (fetch) {
        Fetch.Missing -> TextAction("Download") { actions.download(build) }
        is Fetch.Running -> {
            Text(
                percent(fetch.done, fetch.total),
                style = MagicHandyTheme.type.small,
                color = colors.textSecondary,
            )
            Progress(fetch.done.toFloat() / fetch.total.coerceAtLeast(1))
            TextAction("Cancel", colors.muted) { actions.cancelDownload(build) }
        }
        Fetch.Ready -> {
            Text("Ready", style = MagicHandyTheme.type.small, color = colors.ok)
            TextAction("Delete", colors.muted) { actions.delete(build) }
        }
        is Fetch.Failed -> TextAction("Retry", colors.warn) { actions.download(build) }
    }
}

@Composable
private fun KitStatusView(mode: KitMode, status: KitStatus, actions: LabActions) {
    val colors = MagicHandyTheme.colors
    val small = MagicHandyTheme.type.small
    when (status) {
        KitStatus.Checking -> Text("Checking…", style = small, color = colors.muted)
        KitStatus.Unavailable -> Text("Not on this phone", style = small, color = colors.muted)
        KitStatus.Downloadable -> TextAction("Download") { actions.downloadKit(mode) }
        is KitStatus.Downloading -> {
            val total = status.total
            Text(
                if (total != null && total > 0) percent(status.done, total) else "Downloading…",
                style = small,
                color = colors.textSecondary,
            )
            if (total != null && total > 0) Progress(status.done.toFloat() / total)
        }
        KitStatus.Ready -> Text("Ready", style = small, color = colors.ok)
        is KitStatus.Failed -> Text("Unavailable", style = small, color = colors.warn)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Microphone(state: LabState, actions: LabActions) {
    val mic = state.mic
    Column(verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.snug)) {
        Caption("Microphone")
        Card {
            ChoiceRow(
                label = "Audio",
                segments = MicSource.entries.map { Segment(it, it.title) },
                selected = mic.source,
                onSelect = { actions.setMic(mic.copy(source = it)) },
                isFirst = true,
            )
            ToggleRow(
                label = "Raise quiet speech",
                description =
                    "Up to 30 dB of gain, so quiet speech reaches the detector and the engines " +
                        "at a working level.",
                isOn = mic.raise,
                onChange = { actions.setMic(mic.copy(raise = it)) },
                isFirst = false,
            )
            ChoiceRow(
                label = "Distance",
                segments = Distance.entries.map { Segment(it, it.title) },
                selected = mic.distance,
                onSelect = { actions.setMic(mic.copy(distance = it)) },
                isFirst = false,
            )
            ToggleRow(
                label = "A device is running nearby",
                description = "Recorded with each result, for comparing noise.",
                isOn = mic.deviceNearby,
                onChange = { actions.setMic(mic.copy(deviceNearby = it)) },
                isFirst = false,
            )
            ChoiceRow(
                label = "Speech detector",
                segments = Detector.entries.map { Segment(it, it.title) },
                selected = mic.detector,
                onSelect = { actions.setMic(mic.copy(detector = it)) },
                isFirst = false,
            )
            MenuRow(
                label = "Speech starts at",
                description = "The detector's confidence. Lower hears quieter speech.",
                options = THRESHOLDS,
                selected = mic.threshold,
                optionLabel = { "%.1f".format(Locale.ROOT, it) },
                onSelect = { actions.setMic(mic.copy(threshold = it)) },
            )
            MenuRow(
                label = "Silence that ends a turn",
                description = null,
                options = SILENCES,
                selected = mic.silenceSeconds,
                optionLabel = { "%.1f s".format(Locale.ROOT, it) },
                onSelect = { actions.setMic(mic.copy(silenceSeconds = it)) },
            )
        }
    }
}

/** A setting chosen from a menu, with an optional line under its label. */
@Composable
private fun <T> MenuRow(
    label: String,
    description: String?,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    CardRow(isFirst = false, verticalPadding = MagicHandyTheme.dimens.s) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MagicHandyTheme.type.body)
            description?.let {
                Text(it, style = MagicHandyTheme.type.caption, color = MagicHandyTheme.colors.muted)
            }
        }
        ValueMenu(options = options, selected = selected, label = optionLabel, onSelect = onSelect)
    }
}

/** A label beside its choices; with large text the choices wrap under it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
    label: String,
    segments: List<Segment<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    isFirst: Boolean,
) {
    val dimens = MagicHandyTheme.dimens
    CardRow(isFirst = isFirst, verticalPadding = dimens.s) {
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(dimens.cozy),
            verticalArrangement = Arrangement.spacedBy(dimens.snug),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MagicHandyTheme.type.body, modifier = Modifier.weight(1f))
            Segmented(
                segments = segments,
                selected = selected,
                onSelect = onSelect,
                modifier =
                    Modifier.weight(CHOICE_WEIGHT)
                        .widthIn(min = segmentedWidthFor(segments.map { it.label })),
            )
        }
    }
}

@Composable
private fun HandsFreePanel(state: LabState, actions: LabActions) {
    val dimens = MagicHandyTheme.dimens
    val listening = state.listening
    Column(verticalArrangement = Arrangement.spacedBy(dimens.s)) {
        if (listening == null) {
            AppButton(
                "Start listening",
                actions::startListening,
                kind = ButtonKind.Primary,
                isEnabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(
                "Keeps listening with the screen off. Google ML Kit works only while this page is " +
                    "open, so it stops when the screen does. Hands-free uses the first checked " +
                    "Parakeet build and ML Kit mode; One clip runs every checked engine."
            )
        } else {
            ListeningCard(listening, state.mic.threshold, actions)
        }
    }
}

@Composable
private fun ListeningCard(listening: Listening, threshold: Float, actions: LabActions) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val elapsed = listening.seconds
    Card(contentPadding = PaddingValues(dimens.m)) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.s)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.s),
            ) {
                StatusDot(if (listening.speaking) DotTone.Accent else DotTone.Ok)
                Text(
                    (if (listening.speaking) "Hearing speech" else "Listening") +
                        " · " +
                        listening.engines,
                    style = MagicHandyTheme.type.label,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%d:%02d"
                        .format(
                            Locale.ROOT,
                            elapsed / SECONDS_PER_MINUTE,
                            elapsed % SECONDS_PER_MINUTE,
                        ),
                    style = MagicHandyTheme.type.mono,
                    color = colors.textSecondary,
                )
            }
            Meter(listening.bars)
            listening.reading?.let { ReadingLines(it, threshold) }
            listening.partial?.let {
                Text(
                    "ML Kit: “$it”",
                    style = MagicHandyTheme.type.small,
                    fontStyle = FontStyle.Italic,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppButton("Stop listening", actions::stopListening, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The numbers under the meter: what the microphone delivers before any gain, the noise floor and
 * gain, and the detector's confidence against where speech starts.
 */
@Composable
private fun ReadingLines(reading: Reading, threshold: Float) {
    val style = MagicHandyTheme.type.mono
    val color = MagicHandyTheme.colors.textSecondary
    val noise = reading.noiseDb?.let(::decibels) ?: "…"
    Text(
        "Mic " +
            decibels(reading.micDb) +
            " dBFS · " +
            if (reading.raises) {
                "noise " + noise + " · gain " + signedDecibels(reading.gainDb) + " dB"
            } else {
                "no gain"
            },
        style = style,
        color = color,
    )
    Text(
        "Detector " +
            "%.2f".format(Locale.ROOT, reading.probability) +
            " · speech starts at " +
            "%.1f".format(Locale.ROOT, threshold),
        style = style,
        color = color,
    )
}

@Composable
private fun ClipPanel(state: LabState, actions: LabActions) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    var isEditing by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(dimens.s)) {
        Card(contentPadding = PaddingValues(dimens.m)) {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.s)) {
                Caption("Say this · line ${state.line + 1} of ${LabScript.lines.size}")
                if (isEditing) {
                    TextInput(state.lineText, actions::editLine, label = "What you'll say")
                } else {
                    Text(state.lineText, style = MagicHandyTheme.type.title)
                }
                ButtonRow(weights = listOf(1f, 1f, 1f)) {
                    AppButton(
                        "Previous",
                        { actions.moveLine(-1) },
                        height = dimens.compactControl,
                    )
                    AppButton(
                        if (isEditing) "Done" else "Edit",
                        { isEditing = !isEditing },
                        height = dimens.compactControl,
                    )
                    AppButton("Next", { actions.moveLine(1) }, height = dimens.compactControl)
                }
            }
        }
        when (val clip = state.clip) {
            ClipPhase.Idle ->
                AppButton(
                    "Record",
                    actions::startClip,
                    kind = ButtonKind.Primary,
                    isEnabled = !state.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                )
            is ClipPhase.Recording -> {
                AppButton(
                    "Finish recording · " + clip.seconds + " s",
                    actions::finishClip,
                    kind = ButtonKind.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Meter(clip.bars)
            }
            is ClipPhase.Transcribing ->
                AppButton(
                    "Transcribing with " + clip.engine + "…",
                    {},
                    kind = ButtonKind.Primary,
                    isEnabled = false,
                    modifier = Modifier.fillMaxWidth(),
                )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint(
                "Every engine hears the same recording.",
                Modifier.weight(1f),
            )
            TextAction("Try the sample", colors.accent, actions::runSample)
        }
    }
}

@Composable
private fun ResultsHeading(state: LabState, actions: LabActions) {
    val turns = state.entries.count { it is Turn }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Caption(
            "Results · $turns " + if (turns == 1) "turn" else "turns",
            Modifier.weight(1f),
        )
        if (state.entries.isNotEmpty()) {
            TextAction("Share", onClick = actions::share)
            TextAction("Clear", MagicHandyTheme.colors.muted, actions::clear)
        }
    }
}

@Composable
private fun TurnCard(turn: Turn) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    Card(contentPadding = PaddingValues(start = dimens.m, end = dimens.m, top = dimens.s)) {
        Text(
            clock(turn.at) +
                " · " +
                seconds(turn.speechMillis) +
                " of speech" +
                (turn.level?.let { " " + it.describe() } ?: "") +
                " · " +
                turn.tags,
            style = MagicHandyTheme.type.mono,
            color = colors.muted,
        )
        turn.reference?.let {
            Text(
                "Said: $it",
                style = MagicHandyTheme.type.small,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = dimens.xxs),
            )
        }
        turn.results.forEachIndexed { index, result ->
            CardRow(
                isFirst = index == 0 && turn.reference == null,
                minHeight = dimens.compactControl,
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(dimens.xxs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            result.engine,
                            style = MagicHandyTheme.type.caption,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.muted,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            figures(result),
                            style = MagicHandyTheme.type.mono,
                            color = colors.muted,
                        )
                    }
                    ResultText(result)
                }
            }
        }
    }
}

@Composable
private fun ResultText(result: EngineResult) {
    val colors = MagicHandyTheme.colors
    val error = result.error
    val text = result.text
    when {
        error != null -> Text(error, style = MagicHandyTheme.type.small, color = colors.warn)
        text == null -> Text("…", style = MagicHandyTheme.type.body, color = colors.muted)
        else ->
            FlowRow(verticalArrangement = Arrangement.Center) {
                Text(
                    result.score?.let { marked(it) }
                        ?: AnnotatedString(text.ifBlank { "(nothing)" }),
                    style = MagicHandyTheme.type.body,
                )
                if (result.wouldStop) StopBadge()
            }
    }
}

@Composable
private fun StopBadge() {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    Text(
        "Would stop",
        style = MagicHandyTheme.type.caption,
        fontWeight = FontWeight.SemiBold,
        color = colors.dangerText,
        modifier =
            Modifier.padding(start = dimens.snug)
                .border(dimens.hairline, colors.dangerLine, MagicHandyTheme.shapes.small)
                .padding(horizontal = dimens.xs, vertical = dimens.xxs),
    )
}

@Composable
private fun marked(score: WordScore): AnnotatedString {
    val colors = MagicHandyTheme.colors
    val joiner =
        if (score.marks.all { it.text.length == 1 && it.text[0].code >= CJK_START }) "" else " "
    return buildAnnotatedString {
        score.marks.forEachIndexed { index, word ->
            if (index > 0) append(joiner)
            when (word.mark) {
                Mark.Right -> append(word.text)
                Mark.Wrong,
                Mark.Extra ->
                    withStyle(
                        SpanStyle(color = colors.warn, textDecoration = TextDecoration.Underline)
                    ) {
                        append(word.text)
                    }
                Mark.Missing ->
                    withStyle(
                        SpanStyle(
                            color = colors.dangerText,
                            textDecoration = TextDecoration.LineThrough,
                        )
                    ) {
                        append(word.text)
                    }
            }
        }
    }
}

@Composable
private fun NoteLine(note: LabNote) {
    val dimens = MagicHandyTheme.dimens
    Row(
        Modifier.padding(horizontal = dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.s),
    ) {
        StatusDot(DotTone.Idle)
        Text(
            clock(note.at) + " · " + note.text,
            style = MagicHandyTheme.type.small,
            color = MagicHandyTheme.colors.textSecondary,
        )
    }
}

/** Loudness bars, newest on the right; hot bars are speech, or loud audio in a clip. */
@Composable
private fun Meter(bars: List<MeterBar>) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val height: Dp = dimens.touch / 2
    Row(
        Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(dimens.tight),
        verticalAlignment = Alignment.Bottom,
    ) {
        val shown = List(METER_BARS - bars.size) { MeterBar(0f) } + bars.takeLast(METER_BARS)
        for (bar in shown) {
            Box(
                Modifier.weight(1f)
                    .fillMaxHeight(bar.level.coerceIn(MIN_BAR, 1f))
                    .clip(MagicHandyTheme.shapes.small)
                    .background(if (bar.isHot) colors.accent else colors.strongLine)
            )
        }
    }
}

@Composable
private fun Progress(fraction: Float) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    Box(
        Modifier.padding(vertical = dimens.xs)
            .width(dimens.readoutWidth * 2)
            .height(dimens.progressBar)
            .clip(MagicHandyTheme.shapes.small)
            .background(colors.line)
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(dimens.progressBar)
                .background(colors.accent)
        )
    }
}

/** A compact text button with a full touch target. */
@Composable
private fun TextAction(text: String, color: Color? = null, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = MagicHandyTheme.dimens.touch)
            .clip(MagicHandyTheme.shapes.control)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = MagicHandyTheme.dimens.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MagicHandyTheme.type.label,
            color = color ?: MagicHandyTheme.colors.accent,
        )
    }
}

private fun figures(result: EngineResult): String =
    listOfNotNull(
            result.score?.let { "${it.errors} of ${it.words} wrong" },
            (result.latencyMillis ?: result.processMillis)?.let { seconds(it) },
            result.realTime?.let { "×" + "%.2f".format(Locale.ROOT, it) },
        )
        .joinToString(" · ")

private fun seconds(millis: Long): String =
    "%.2f s".format(Locale.ROOT, millis / MILLIS_PER_SECOND.toFloat())

private fun percent(done: Long, total: Long): String =
    (done * PERCENT / total.coerceAtLeast(1)).toString() + "%"

private fun size(bytes: Long): String =
    if (bytes >= BYTES_PER_GB) "%.1f GB".format(Locale.ROOT, bytes / BYTES_PER_GB.toFloat())
    else "${bytes / BYTES_PER_MB} MB"

private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

/** A gain such as "+24" or "−1", to the nearest decibel. */
private fun signedDecibels(value: Float): String =
    "%+.0f".format(Locale.ROOT, value).replace('-', '−')

private fun clock(at: Long): String = SimpleDateFormat("H:mm:ss", Locale.ROOT).format(Date(at))

private val SILENCES = listOf(0.4f, 0.6f, 0.8f, 1.0f, 1.5f)
private val THRESHOLDS = listOf(0.3f, 0.4f, 0.5f, 0.6f)
private const val CHOICE_WEIGHT = 1.6f
private const val METER_BARS = 24
private const val MIN_BAR = 0.08f
private const val PERCENT = 100
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60
private const val BYTES_PER_MB = 1_000_000L
private const val BYTES_PER_GB = 1_000_000_000L
private const val MB_PER_GB = 1024f
private const val CJK_START = 0x2E80

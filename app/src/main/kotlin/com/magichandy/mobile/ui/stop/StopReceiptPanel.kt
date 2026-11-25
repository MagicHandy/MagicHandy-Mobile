package com.magichandy.mobile.ui.stop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.DeviceStopOutcome
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopReceipt
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.StatusDot
import com.magichandy.mobile.ui.components.topHairline
import com.magichandy.mobile.ui.components.withMonoReadouts
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import kotlinx.coroutines.delay

/** Dims the screen behind a Stop receipt. Tapping it dismisses the receipt, like Done. */
@Composable
fun BoxScope.ReceiptScrim(onDone: () -> Unit) {
    Box(
        Modifier.matchParentSize()
            .background(MagicHandyTheme.colors.scrim)
            .clickable(interactionSource = null, indication = null, onClick = onDone)
    )
}

/**
 * The Stop receipt above the footer (board V-StopReceipt), over a [ReceiptScrim]. Uncertain devices
 * come first, and it says "Stopped" only when every device confirmed. A long list scrolls. TalkBack
 * announces it assertively. With nothing connected it's a toast that goes by itself.
 */
@Composable
fun StopReceiptPanel(
    receipt: StopReceipt,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    hasEndedVoice: Boolean = false,
) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.card
    if (receipt.isNoDevice) {
        LaunchedEffect(receipt.pressCount) {
            delay(NO_DEVICE_TOAST_MILLIS)
            onDone()
        }
        Row(
            modifier
                .padding(
                    horizontal = MagicHandyTheme.dimens.m,
                    vertical = MagicHandyTheme.dimens.cozy,
                )
                .clip(shape)
                .background(colors.toast)
                .border(MagicHandyTheme.dimens.hairline, colors.strongLine, shape)
                .semantics { liveRegion = LiveRegionMode.Assertive }
                .padding(
                    horizontal = MagicHandyTheme.dimens.roomy,
                    vertical = MagicHandyTheme.dimens.m,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(DotTone.Idle)
            Spacer(Modifier.width(MagicHandyTheme.dimens.s))
            Text(
                stringResource(R.string.receipt_no_device),
                style =
                    MagicHandyTheme.type.small.copy(fontSize = MagicHandyTheme.type.label.fontSize),
            )
        }
        return
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = MagicHandyTheme.dimens.m, vertical = MagicHandyTheme.dimens.cozy)
            .clip(shape)
            .background(colors.surface)
            .border(MagicHandyTheme.dimens.hairline, colors.strongLine, shape)
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .verticalScroll(rememberScrollState())
            .padding(MagicHandyTheme.dimens.m)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = MagicHandyTheme.dimens.s),
        ) {
            StatusDot(headerTone(receipt))
            Spacer(Modifier.width(MagicHandyTheme.dimens.s))
            Text(
                headerText(receipt),
                style = MagicHandyTheme.type.receiptHeading,
            )
        }
        receipt.outcomes.forEach { OutcomeRow(it) }
        Row(
            Modifier.fillMaxWidth()
                .topHairline(colors.line)
                .padding(top = MagicHandyTheme.dimens.cozy),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                footerText(receipt, hasEndedVoice),
                style = MagicHandyTheme.type.small,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
            AppButton(
                stringResource(R.string.action_done),
                onDone,
                height = MagicHandyTheme.dimens.compactControl,
            )
        }
    }
}

/** What else the Stop ended: Autopilot, the reply, and listening (board V-VoiceStop). */
@Composable
private fun footerText(receipt: StopReceipt, hasEndedVoice: Boolean): String {
    val ended =
        when {
            receipt.didTurnOffAutopilot && receipt.didCancelReply ->
                stringResource(R.string.receipt_autopilot_off_reply_cancelled)
            receipt.didTurnOffAutopilot -> stringResource(R.string.receipt_autopilot_off)
            receipt.didCancelReply -> stringResource(R.string.receipt_reply_cancelled)
            else -> ""
        }
    return when {
        !hasEndedVoice -> ended
        ended.isEmpty() -> stringResource(R.string.receipt_voice_ended)
        else -> stringResource(R.string.receipt_and_voice_ended, ended)
    }
}

private fun headerTone(receipt: StopReceipt): DotTone =
    when {
        receipt.isAllConfirmed -> DotTone.Ok
        receipt.outcomes.any { it.outcome == StopOutcome.Unconfirmed } -> DotTone.Danger
        else -> DotTone.Warn
    }

@Composable
private fun headerText(receipt: StopReceipt): String {
    if (receipt.isAllConfirmed) return stringResource(R.string.receipt_stopped)
    val counts = receipt.outcomes.groupingBy { it.outcome }.eachCount()
    val parts =
        listOfNotNull(
            counts[StopOutcome.Confirmed]?.let {
                pluralStringResource(R.plurals.receipt_confirmed, it, it)
            },
            counts[StopOutcome.Pending]?.let {
                pluralStringResource(R.plurals.receipt_pending, it, it)
            },
            counts[StopOutcome.Unconfirmed]?.let {
                pluralStringResource(R.plurals.receipt_unconfirmed, it, it)
            },
        )
    return stringResource(
        R.string.receipt_sent,
        parts.joinToString(stringResource(R.string.receipt_list_separator)),
    )
}

@Composable
private fun OutcomeRow(outcome: DeviceStopOutcome) {
    val colors = MagicHandyTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .topHairline(colors.line)
            .padding(vertical = MagicHandyTheme.dimens.bubbleInset)
    ) {
        StatusDot(outcomeTone(outcome.outcome), Modifier.padding(top = MagicHandyTheme.dimens.snug))
        Spacer(Modifier.width(MagicHandyTheme.dimens.cozy))
        Column(verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.xxs)) {
            Text(outcomeTitle(outcome), style = MagicHandyTheme.type.label)
            Text(
                outcomeDetail(outcome),
                style = MagicHandyTheme.type.small,
                color = colors.textSecondary,
            )
        }
    }
}

internal fun outcomeTone(outcome: StopOutcome): DotTone =
    when (outcome) {
        StopOutcome.Unconfirmed -> DotTone.Danger
        StopOutcome.Pending -> DotTone.Warn
        StopOutcome.Confirmed -> DotTone.Ok
    }

@Composable
private fun outcomeTitle(outcome: DeviceStopOutcome): String =
    stringResource(
        when (outcome.outcome) {
            StopOutcome.Unconfirmed -> R.string.outcome_unconfirmed
            StopOutcome.Pending -> R.string.outcome_pending
            StopOutcome.Confirmed -> R.string.outcome_confirmed
        },
        outcome.name,
    )

/** What is known about one device, and the one local action when it's uncertain. */
@Composable
internal fun outcomeDetail(outcome: DeviceStopOutcome): AnnotatedString =
    when (outcome.outcome) {
        StopOutcome.Confirmed -> confirmedDetail(outcome)
        StopOutcome.Pending -> AnnotatedString(stringResource(R.string.outcome_pending_detail))
        StopOutcome.Unconfirmed -> {
            val afterLoss = Words.afterLoss(outcome.afterLoss, outcome.name)
            AnnotatedString(
                outcome.cause?.let { stringResource(R.string.sentences, causeText(it), afterLoss) }
                    ?: afterLoss
            )
        }
    }

/**
 * A bridge's reply alone never counts as confirmed (system design §2): the core reports it as
 * unconfirmed, with [UnconfirmedCause.BridgeOnly].
 */
@Composable
private fun confirmedDetail(outcome: DeviceStopOutcome): AnnotatedString {
    val latency = outcome.confirmedIn
    return when {
        latency != null -> {
            val millis = Words.milliseconds(latency)
            withMonoReadouts(
                stringResource(
                    R.string.outcome_confirmed_in,
                    Words.connection(outcome.connection),
                    millis,
                ),
                millis,
            )
        }
        else -> AnnotatedString(stringResource(R.string.receipt_stopped))
    }
}

@Composable
private fun causeText(cause: UnconfirmedCause): String =
    stringResource(
        when (cause) {
            UnconfirmedCause.NoReply -> R.string.cause_no_reply
            UnconfirmedCause.WriteFailed -> R.string.cause_write_failed
            UnconfirmedCause.BridgeOnly -> R.string.cause_bridge_only
            UnconfirmedCause.LinkLost -> R.string.cause_link_lost
            UnconfirmedCause.Offline -> R.string.cause_offline
            UnconfirmedCause.AppStopped -> R.string.cause_app_stopped
        }
    )

private const val NO_DEVICE_TOAST_MILLIS = 4_000L

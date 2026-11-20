package com.magichandy.mobile.ui.stop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AfterLossBehaviour
import com.magichandy.mobile.core.api.DeviceStopOutcome
import com.magichandy.mobile.core.api.InterruptedRun
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.ButtonRow
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.StatusDot
import com.magichandy.mobile.ui.components.topHairline
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Run interrupted (board V-Restored): shown on a cold start with an unresolved journal. It leads
 * with the device that may still be running, never guesses why the app closed, and never claims an
 * outcome no device acknowledged.
 */
@Composable
fun RunInterruptedScreen(
    run: InterruptedRun,
    onIntent: (UserIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MagicHandyTheme.colors
    // The list scrolls; the two ways out stay pinned above Stop (board V-Restored).
    Column(
        modifier
            .fillMaxSize()
            .padding(
                start = MagicHandyTheme.dimens.l,
                end = MagicHandyTheme.dimens.l,
                top = MagicHandyTheme.dimens.xl,
                bottom = MagicHandyTheme.dimens.m,
            )
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.m),
        ) {
            Text(
                stringResource(R.string.interrupted_title),
                style = MagicHandyTheme.type.pageTitle,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.interrupted_lead, Words.clockTime(run.stoppedAt)),
                style = MagicHandyTheme.type.body,
                color = colors.textSecondary,
            )
            Card(
                contentPadding =
                    PaddingValues(
                        horizontal = MagicHandyTheme.dimens.m,
                        vertical = MagicHandyTheme.dimens.xxs,
                    )
            ) {
                run.outcomes.forEachIndexed { index, outcome -> RecoveryRow(outcome, index == 0) }
                if (run.wasAutopilotOn) {
                    Row(
                        Modifier.fillMaxWidth()
                            .topHairline(colors.line)
                            .padding(vertical = MagicHandyTheme.dimens.recoveryRowInset)
                    ) {
                        StatusDot(DotTone.Idle, Modifier.padding(top = MagicHandyTheme.dimens.snug))
                        Spacer(Modifier.width(MagicHandyTheme.dimens.cozy))
                        Column {
                            Text(
                                stringResource(R.string.interrupted_autopilot),
                                style = MagicHandyTheme.type.bodyStrong,
                            )
                            Text(
                                stringResource(R.string.interrupted_autopilot_detail),
                                style = MagicHandyTheme.type.small,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
        ButtonRow(
            weights = listOf(1f, 1f),
            modifier = Modifier.padding(top = MagicHandyTheme.dimens.m),
        ) {
            AppButton(
                stringResource(R.string.interrupted_reconnect),
                { onIntent(UserIntent.ReconnectDevices) },
            )
            AppButton(
                stringResource(R.string.back_to_chat),
                { onIntent(UserIntent.DismissInterruptedRun) },
                kind = ButtonKind.Primary,
            )
        }
    }
}

@Composable
private fun RecoveryRow(outcome: DeviceStopOutcome, isFirst: Boolean) {
    val colors = MagicHandyTheme.colors
    val (tone, title, detail) = recoveryWords(outcome)
    Row(
        Modifier.fillMaxWidth()
            .then(if (isFirst) Modifier else Modifier.topHairline(colors.line))
            .padding(vertical = MagicHandyTheme.dimens.recoveryRowInset)
    ) {
        StatusDot(tone, Modifier.padding(top = MagicHandyTheme.dimens.snug))
        Spacer(Modifier.width(MagicHandyTheme.dimens.cozy))
        Column(verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.tight)) {
            Text(title, style = MagicHandyTheme.type.bodyStrong)
            Text(detail, style = MagicHandyTheme.type.small, color = colors.textSecondary)
        }
    }
}

/** After a crash, an unconfirmed device is described by what it does on its own. */
@Composable
private fun recoveryWords(outcome: DeviceStopOutcome): Triple<DotTone, String, String> {
    val name = outcome.name
    if (outcome.outcome != StopOutcome.Unconfirmed) {
        return Triple(
            outcomeTone(outcome.outcome),
            stringResource(
                if (outcome.outcome == StopOutcome.Confirmed) R.string.outcome_confirmed
                else R.string.outcome_pending,
                name,
            ),
            outcomeDetail(outcome).text,
        )
    }
    return when (outcome.afterLoss) {
        AfterLossBehaviour.KeepsLastSpeed ->
            Triple(
                DotTone.Danger,
                stringResource(R.string.outcome_unconfirmed, name),
                stringResource(R.string.recovery_keeps_last_speed),
            )
        AfterLossBehaviour.StopsWhenAppDisconnects ->
            Triple(
                DotTone.Warn,
                stringResource(R.string.recovery_not_confirmed, name),
                stringResource(R.string.recovery_intiface),
            )
        AfterLossBehaviour.PausesWhenBufferRunsOut ->
            Triple(
                DotTone.Idle,
                stringResource(R.string.recovery_expected_paused, name),
                stringResource(R.string.recovery_pauses),
            )
        AfterLossBehaviour.GoesSilent ->
            Triple(
                DotTone.Idle,
                stringResource(R.string.recovery_expected_silent, name),
                stringResource(R.string.recovery_silent),
            )
        AfterLossBehaviour.FinishesLastMove ->
            Triple(
                DotTone.Idle,
                stringResource(R.string.recovery_expected_stopped, name),
                stringResource(R.string.recovery_last_move),
            )
    }
}

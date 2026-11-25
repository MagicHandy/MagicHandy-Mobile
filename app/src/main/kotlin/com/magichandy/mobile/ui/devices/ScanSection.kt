package com.magichandy.mobile.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.ScanFailure
import com.magichandy.mobile.core.api.ScanResult
import com.magichandy.mobile.core.api.ScanResultStatus
import com.magichandy.mobile.core.api.ScanState
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.Caption
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.withMonoReadouts
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Scans while on screen until a result is chosen, keeps the results' order, and verifies a choice
 * before it can play. A result that couldn't be verified offers Retry.
 */
@Composable
internal fun ScanSection(
    family: DeviceFamily,
    state: AppState,
    onIntent: (UserIntent) -> Unit,
    onDone: () -> Unit,
    defaultName: String,
) {
    DisposableEffect(family) {
        onIntent(UserIntent.StartScan(family))
        onDispose { onIntent(UserIntent.StopScan) }
    }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val scan = state.devices.scan
    val results = (scan as? ScanState.Scanning)?.takeIf { it.family == family }?.results.orEmpty()
    val verified = results.firstOrNull {
        it.id.value == chosen && it.status == ScanResultStatus.Verified
    }
    LaunchedEffect(verified) { if (verified != null) onDone() }
    val isVerifying = results.any { it.status == ScanResultStatus.Verifying }

    Caption(stringResource(R.string.add_nearby))
    when {
        scan is ScanState.Failed && scan.family == family ->
            Hint(
                stringResource(
                    when (scan.cause) {
                        ScanFailure.BluetoothOff -> R.string.scan_bluetooth_off
                        ScanFailure.PermissionDenied -> R.string.scan_permission_denied
                        ScanFailure.NothingFound -> R.string.scan_nothing_found
                    }
                )
            )
        results.isEmpty() -> Hint(stringResource(R.string.add_scanning))
        else ->
            Card(contentPadding = PaddingValues(horizontal = MagicHandyTheme.dimens.m)) {
                results.forEachIndexed { index, result ->
                    ScanRow(
                        result = result,
                        isFirst = index == 0,
                        isDimmed = isVerifying && result.status != ScanResultStatus.Verifying,
                    ) {
                        chosen = result.id.value
                        onIntent(UserIntent.ConnectScanResult(result.id, defaultName))
                    }
                }
            }
    }
}

/**
 * One scan result. While another verifies, the rest dim and can't connect (board V-AddHandy). A
 * failed verification says so under the signal and offers Retry.
 */
@Composable
private fun ScanRow(
    result: ScanResult,
    isFirst: Boolean,
    isDimmed: Boolean,
    onConnect: () -> Unit,
) {
    val colors = MagicHandyTheme.colors
    val dbm = if (result.signalDbm < 0) "−${-result.signalDbm}" else result.signalDbm.toString()
    val signal =
        stringResource(
            when {
                result.signalDbm >= STRONG_DBM -> R.string.signal_strong
                result.signalDbm >= GOOD_DBM -> R.string.signal_good
                else -> R.string.signal_weak
            },
            dbm,
        )
    CardRow(
        isFirst = isFirst,
        minHeight = MagicHandyTheme.dimens.deviceRow,
        modifier = Modifier.alpha(if (isDimmed) DIMMED else 1f),
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.xxs),
        ) {
            Text(result.name, style = MagicHandyTheme.type.bodyStrong)
            Text(
                withMonoReadouts(signal, "$dbm dBm"),
                style = MagicHandyTheme.type.small,
                color = colors.muted,
            )
            if (result.status == ScanResultStatus.Failed)
                Text(
                    stringResource(R.string.add_verify_failed),
                    style = MagicHandyTheme.type.small,
                    color = colors.dangerText,
                )
        }
        when (result.status) {
            ScanResultStatus.Found ->
                AppButton(
                    stringResource(R.string.action_connect),
                    onConnect,
                    kind = ButtonKind.Primary,
                    isEnabled = !isDimmed,
                    height = MagicHandyTheme.dimens.compactControl,
                )
            ScanResultStatus.Failed ->
                AppButton(
                    stringResource(R.string.device_retry_connection),
                    onConnect,
                    kind = ButtonKind.Outline,
                    isEnabled = !isDimmed,
                    height = MagicHandyTheme.dimens.compactControl,
                )
            ScanResultStatus.Verifying ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(MagicHandyTheme.dimens.smallIcon),
                        color = colors.muted,
                        trackColor = colors.line,
                        strokeWidth = MagicHandyTheme.dimens.markStroke,
                    )
                    Text(
                        stringResource(R.string.add_verifying),
                        style = MagicHandyTheme.type.small,
                        modifier = Modifier.padding(start = MagicHandyTheme.dimens.s),
                    )
                }
            ScanResultStatus.Verified ->
                Text(
                    stringResource(R.string.add_verified),
                    style = MagicHandyTheme.type.small,
                    color = colors.ok,
                )
        }
    }
}

/** Signal thresholds for the scan wording, in dBm. */
private const val STRONG_DBM = -60
private const val GOOD_DBM = -72
private const val DIMMED = 0.45f

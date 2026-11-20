package com.magichandy.mobile.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopReceipt
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.NotificationsRefusedBanner
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.Banner
import com.magichandy.mobile.ui.components.BannerTone
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.Caption
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.StatusDot
import com.magichandy.mobile.ui.components.StatusLabel
import com.magichandy.mobile.ui.components.SwitchGraphic
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.settings.SystemAccess
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Devices (board V-Devices): every saved device, grouped by whether it is in the run. It also warns
 * when notifications were refused, since the prompt comes with the first connection (board
 * V-System).
 */
@Composable
fun DevicesScreen(
    state: AppState,
    system: SystemAccess,
    onIntent: (UserIntent) -> Unit,
    onOpenDevice: (DeviceId) -> Unit,
    onAddDevice: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = MagicHandyTheme.dimens
    val devices = state.devices
    val (inRun, notInRun) = devices.devices.partition { it.membership is RunMembership.InRun }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = dimens.m, end = dimens.m, top = dimens.l, bottom = dimens.s),
        verticalArrangement = Arrangement.spacedBy(dimens.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.devices_title),
                style = MagicHandyTheme.type.pageTitle,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            RadioState(
                isOn = devices.isBluetoothOn,
                on = R.string.devices_bluetooth_on,
                off = R.string.devices_bluetooth_off,
            )
            Box(Modifier.padding(start = MagicHandyTheme.dimens.m)) {
                RadioState(devices.isOnline, R.string.devices_online, R.string.devices_offline)
            }
        }
        UnresolvedStops(state.stopReceipt, onIntent)
        if (system.areNotificationsRefused) NotificationsRefusedBanner(onOpenNotificationSettings)
        if (devices.devices.isEmpty()) {
            Text(
                stringResource(R.string.devices_empty),
                style = MagicHandyTheme.type.body,
                color = MagicHandyTheme.colors.muted,
                modifier = Modifier.padding(vertical = MagicHandyTheme.dimens.l),
            )
        }
        if (inRun.isNotEmpty()) {
            DeviceGroup(stringResource(R.string.devices_in_run)) {
                inRun.forEachIndexed { index, device ->
                    DeviceRow(device, index == 0, onIntent, onOpenDevice)
                }
            }
        }
        if (notInRun.isNotEmpty()) {
            DeviceGroup(stringResource(R.string.devices_not_in_run)) {
                notInRun.forEachIndexed { index, device ->
                    DeviceRow(device, index == 0, onIntent, onOpenDevice)
                }
            }
        }
        AppButton(
            stringResource(R.string.devices_add),
            onAddDevice,
            modifier = Modifier.fillMaxWidth().padding(top = MagicHandyTheme.dimens.xs),
        )
        Hint(stringResource(R.string.devices_note))
    }
}

/**
 * Devices whose last Stop is unconfirmed stay flagged here, after the receipt is dismissed and
 * after Run interrupted, until they confirm or the person checks them at the device (system design
 * §1.1). Some can never confirm, such as a vibrator behind Intiface, so the check is the way out.
 */
@Composable
private fun UnresolvedStops(receipt: StopReceipt?, onIntent: (UserIntent) -> Unit) {
    receipt
        ?.outcomes
        .orEmpty()
        .filter { it.outcome == StopOutcome.Unconfirmed }
        .forEach { outcome ->
            Banner(
                tone = BannerTone.Danger,
                title = stringResource(R.string.outcome_unconfirmed, outcome.name),
                body = Words.afterLoss(outcome.afterLoss, outcome.name),
                actionLabel = stringResource(R.string.devices_checked_it),
                onAction = { onIntent(UserIntent.ReconcileDevice(outcome.device)) },
                hasIcon = true,
            )
        }
}

@Composable
private fun RadioState(isOn: Boolean, on: Int, off: Int) {
    StatusLabel(
        tone = if (isOn) DotTone.Ok else DotTone.Faint,
        text = stringResource(if (isOn) on else off),
        color = MagicHandyTheme.colors.muted,
        textStyle = MagicHandyTheme.type.caption,
    )
}

@Composable
private fun DeviceGroup(title: String, rows: @Composable ColumnScope.() -> Unit) {
    Caption(title, Modifier.padding(top = MagicHandyTheme.dimens.snug))
    Card(contentPadding = PaddingValues(horizontal = MagicHandyTheme.dimens.m), content = rows)
}

@Composable
private fun DeviceRow(
    device: DeviceSummary,
    isFirst: Boolean,
    onIntent: (UserIntent) -> Unit,
    onOpenDevice: (DeviceId) -> Unit,
) {
    val colors = MagicHandyTheme.colors
    val details = stringResource(R.string.devices_details, device.name)
    CardRow(
        isFirst = isFirst,
        minHeight = MagicHandyTheme.dimens.deviceRow,
        verticalPadding = MagicHandyTheme.dimens.cozy,
    ) {
        Row(
            Modifier.weight(1f)
                .heightIn(min = MagicHandyTheme.dimens.touch)
                .clickable(role = Role.Button) { onOpenDevice(device.id) }
                .semantics { contentDescription = details },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceTile(familyIcon(device.family))
            Column(
                Modifier.padding(start = MagicHandyTheme.dimens.cozy),
                verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.tight),
            ) {
                val family = Words.family(device.family)
                Text(
                    buildAnnotatedString {
                        append(device.name)
                        if (device.name != family) {
                            withStyle(
                                SpanStyle(
                                    color = colors.muted,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = MagicHandyTheme.type.small.fontSize,
                                )
                            ) {
                                append("  $family")
                            }
                        }
                    },
                    style = MagicHandyTheme.type.bodyStrong,
                )
                StatusLine(device)
            }
        }
        Trailing(device, onIntent, onOpenDevice)
    }
}

@Composable
private fun StatusLine(device: DeviceSummary) {
    val colors = MagicHandyTheme.colors
    val connection = Words.connection(device.connection)
    val battery = device.battery
    val detail =
        when {
            device.membership == RunMembership.NextRun ->
                stringResource(R.string.devices_joins_next_run)
            battery != null -> stringResource(R.string.devices_battery, connection, battery.value)
            else -> connection
        }
    val isLead = (device.membership as? RunMembership.InRun)?.isLead == true
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(Words.statusTone(device.status))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.text, fontWeight = FontWeight.Medium)) {
                    append("  " + Words.status(device.status))
                }
                withStyle(SpanStyle(color = colors.muted)) {
                    append("   ")
                    append(if (isLead) stringResource(R.string.devices_lead, detail) else detail)
                }
            },
            style = MagicHandyTheme.type.small,
        )
    }
}

@Composable
private fun Trailing(
    device: DeviceSummary,
    onIntent: (UserIntent) -> Unit,
    onOpenDevice: (DeviceId) -> Unit,
) {
    val compact = MagicHandyTheme.dimens.compactControl
    when {
        device.status == DeviceStatus.NeedsSetup ->
            AppButton(
                stringResource(R.string.devices_set_up),
                { onOpenDevice(device.id) },
                height = compact,
                hasChevron = true,
            )
        device.status == DeviceStatus.NotConnected ->
            AppButton(
                stringResource(R.string.action_connect),
                { onIntent(UserIntent.Connect(device.id)) },
                kind = ButtonKind.Primary,
                height = compact,
            )
        device.status == DeviceStatus.Lost ->
            AppButton(
                stringResource(R.string.action_reconnect),
                { onIntent(UserIntent.Connect(device.id)) },
                kind = ButtonKind.Primary,
                height = compact,
            )
        device.membership !is RunMembership.InRun && device.status == DeviceStatus.Connected ->
            NextRunToggle(device, onIntent)
        else ->
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = MagicHandyTheme.colors.faint,
                modifier = Modifier.size(MagicHandyTheme.dimens.actionIcon),
            )
    }
}

/** The switch, with "Next run" under it (board V-Devices). */
@Composable
private fun NextRunToggle(device: DeviceSummary, onIntent: (UserIntent) -> Unit) {
    val isIncluded = device.membership == RunMembership.NextRun
    val label = stringResource(R.string.devices_joins_next_run_toggle)
    val touch = MagicHandyTheme.dimens.touch
    Column(
        Modifier.sizeIn(minWidth = touch, minHeight = touch)
            .toggleable(value = isIncluded, role = Role.Switch) {
                onIntent(UserIntent.SetInNextRun(device.id, it))
            }
            .semantics { contentDescription = label }
            .padding(vertical = MagicHandyTheme.dimens.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.tight),
    ) {
        SwitchGraphic(isIncluded)
        Text(
            stringResource(R.string.devices_next_run),
            style =
                MagicHandyTheme.type.caption.copy(
                    fontSize = MagicHandyTheme.type.overline.fontSize
                ),
            color = MagicHandyTheme.colors.muted,
        )
    }
}

/** A device family's glyph in a rounded tile. */
@Composable
internal fun DeviceTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = MagicHandyTheme.dimens.deviceTile,
) {
    Box(
        modifier
            .size(size)
            .clip(MagicHandyTheme.shapes.control)
            .background(MagicHandyTheme.colors.raised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MagicHandyTheme.colors.textSecondary,
            modifier = Modifier.size(size / 2),
        )
    }
}

/** The board's glyph for each device family. */
internal fun familyIcon(family: DeviceFamily): ImageVector =
    when (family) {
        DeviceFamily.LinearActuator,
        DeviceFamily.Handy -> AppIcons.Handy
        DeviceFamily.Vibrator -> AppIcons.Vibrator
        DeviceFamily.Coyote -> AppIcons.Coyote
        DeviceFamily.AutoblowUltra -> AppIcons.Ultra
        DeviceFamily.VacuGlide -> AppIcons.VacuGlide
    }

/** The device-type sheet's rows (plan §14.2): one per family, opening its setup route. */
@Composable
fun DeviceTypeList(onChoose: (DeviceFamily) -> Unit) {
    val families =
        listOf(
            DeviceFamily.Handy to R.string.device_type_handy,
            DeviceFamily.LinearActuator to R.string.device_type_linear,
            DeviceFamily.Vibrator to R.string.device_type_vibrator,
            DeviceFamily.Coyote to R.string.device_type_coyote,
            DeviceFamily.AutoblowUltra to R.string.device_type_ultra,
            DeviceFamily.VacuGlide to R.string.device_type_vacuglide,
        )
    families.forEachIndexed { index, (family, description) ->
        CardRow(
            isFirst = index == 0,
            minHeight = MagicHandyTheme.dimens.listRow,
            modifier = Modifier.clickable(role = Role.Button) { onChoose(family) },
        ) {
            DeviceTile(familyIcon(family))
            Column(Modifier.weight(1f)) {
                Text(Words.family(family), style = MagicHandyTheme.type.bodyStrong)
                Text(
                    stringResource(description),
                    style = MagicHandyTheme.type.caption,
                    color = MagicHandyTheme.colors.muted,
                )
            }
            Icon(
                AppIcons.ChevronRight,
                null,
                tint = MagicHandyTheme.colors.faint,
                modifier = Modifier.size(MagicHandyTheme.dimens.smallIcon),
            )
        }
    }
}

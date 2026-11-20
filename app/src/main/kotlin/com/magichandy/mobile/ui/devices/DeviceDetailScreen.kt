package com.magichandy.mobile.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.Banner
import com.magichandy.mobile.ui.components.BannerTone
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.FixedRow
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.IconAction
import com.magichandy.mobile.ui.components.LabelRow
import com.magichandy.mobile.ui.components.SettingRow
import com.magichandy.mobile.ui.components.StatusLabel
import com.magichandy.mobile.ui.components.TextInput
import com.magichandy.mobile.ui.components.ToggleRow
import com.magichandy.mobile.ui.components.ValueMenu
import com.magichandy.mobile.ui.components.withMonoReadouts
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * One device's page (boards V-HandyPage, V-VacuGlide and V-CoyotePage). Durable settings for the
 * device live here, not in chat settings.
 *
 * @param isBatteryUnrestricted whether Android lets the app run without battery limits.
 * @param hasAcknowledgedCoyoteNotes whether the Coyote's safety notes were read, which its limits
 *   need.
 */
@Composable
fun DeviceDetailScreen(
    device: DeviceSummary,
    isBatteryUnrestricted: Boolean,
    hasAcknowledgedCoyoteNotes: Boolean,
    onIntent: (UserIntent) -> Unit,
    onSetCoyoteLimits: (device: DeviceId, maximumA: Int, maximumB: Int) -> Unit,
    onBack: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = MagicHandyTheme.dimens
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = dimens.m, end = dimens.m, bottom = dimens.s),
        verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.cozy),
    ) {
        DeviceHeader(device, onBack)
        val settings = device.settings
        if (settings is DeviceSettings.VacuGlide) {
            VacuGlideSections(
                device,
                settings,
                isBatteryUnrestricted,
                onIntent,
                onOpenBatterySettings,
            )
            MotionTest(device, onIntent)
            DeviceSection(device, settings, onIntent)
        } else {
            if (settings is DeviceSettings.Coyote) {
                CoyoteLimits(device, settings, hasAcknowledgedCoyoteNotes, onSetCoyoteLimits)
            }
            DeviceSection(device, settings, onIntent)
            MotionTest(device, onIntent)
        }
        BottomActions(device, onIntent, onBack)
    }
}

/** Back with the "Devices" crumb, then the device's tile, name and status (board V-HandyPage). */
@Composable
private fun DeviceHeader(device: DeviceSummary, onBack: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(
                AppIcons.Back,
                stringResource(R.string.back_to_devices),
                onBack,
                Modifier.offset(x = -MagicHandyTheme.dimens.backOffset),
            )
            Text(
                stringResource(R.string.tab_devices),
                style = MagicHandyTheme.type.small,
                color = MagicHandyTheme.colors.muted,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = MagicHandyTheme.dimens.xxs),
        ) {
            DeviceTile(familyIcon(device.family), size = MagicHandyTheme.dimens.pageTile)
            Column(Modifier.padding(start = MagicHandyTheme.dimens.m)) {
                Text(
                    device.name,
                    style = MagicHandyTheme.type.deviceName,
                    modifier = Modifier.semantics { heading() },
                )
                StatusLabel(
                    tone = Words.statusTone(device.status),
                    text = statusLine(device),
                    color = MagicHandyTheme.colors.muted,
                )
            }
        }
    }
}

@Composable
private fun statusLine(device: DeviceSummary) = run {
    val status = Words.status(device.status)
    val connection = Words.connection(device.connection)
    val battery = device.battery
    val latency = device.linkLatency
    when {
        battery != null ->
            withMonoReadouts(
                stringResource(R.string.device_status_battery, status, connection, battery.value)
            )
        latency != null -> {
            val millis = latency.inWholeMilliseconds.toInt()
            withMonoReadouts(
                stringResource(R.string.device_status_latency, status, connection, millis),
                millis.toString(),
            )
        }
        else -> withMonoReadouts(stringResource(R.string.device_status_line, status, connection))
    }
}

@Composable
private fun DeviceSection(
    device: DeviceSummary,
    settings: DeviceSettings,
    onIntent: (UserIntent) -> Unit,
) {
    Card(caption = stringResource(R.string.device_section)) {
        NameRow(device, onIntent)
        FixedRow(
            label = stringResource(R.string.device_connection),
            value = Words.connection(device.connection),
            description = stringResource(R.string.device_connection_desc),
            isFirst = false,
        )
        when (settings) {
            is DeviceSettings.Handy -> {
                FixedRow(
                    label = stringResource(R.string.handy_model),
                    value = settings.model,
                    description = stringResource(R.string.handy_model_desc),
                    isFirst = false,
                )
                ToggleRow(
                    label = stringResource(R.string.handy_reverse),
                    description = stringResource(R.string.handy_reverse_desc),
                    isOn = settings.isReversed,
                    onChange = { onIntent(UserIntent.SetReversed(device.id, it)) },
                    isFirst = false,
                )
            }
            is DeviceSettings.Vibrator ->
                FixedRow(
                    label = stringResource(R.string.vibrator_max_level),
                    value = Words.percent(settings.maxLevel),
                    isFirst = false,
                )
            // The Coyote's limits have their own card above (CoyoteLimits).
            is DeviceSettings.Coyote,
            DeviceSettings.AutoblowUltra,
            is DeviceSettings.VacuGlide -> Unit
        }
    }
}

@Composable
private fun NameRow(device: DeviceSummary, onIntent: (UserIntent) -> Unit) {
    var isEditing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(device.id.value) { mutableStateOf(device.name) }
    if (!isEditing) {
        SettingRow(
            label = stringResource(R.string.field_name),
            value = device.name,
            onClick = {
                draft = device.name
                isEditing = true
            },
            isFirst = true,
        )
        return
    }
    Row(
        Modifier.padding(vertical = MagicHandyTheme.dimens.s),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextInput(draft, { draft = it }, stringResource(R.string.field_name), Modifier.weight(1f))
        AppButton(
            stringResource(R.string.action_save),
            {
                onIntent(UserIntent.Rename(device.id, draft.trim()))
                isEditing = false
            },
            kind = ButtonKind.Primary,
            isEnabled = draft.isNotBlank(),
            modifier = Modifier.padding(start = MagicHandyTheme.dimens.s),
        )
    }
}

@Composable
private fun VacuGlideSections(
    device: DeviceSummary,
    settings: DeviceSettings.VacuGlide,
    isBatteryUnrestricted: Boolean,
    onIntent: (UserIntent) -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    val colors = MagicHandyTheme.colors
    Banner(
        tone = BannerTone.Danger,
        title = stringResource(R.string.vacuglide_warning_title),
        body = stringResource(R.string.vacuglide_warning_body),
        hasIcon = true,
    )
    Card(caption = stringResource(R.string.vacuglide_safeguards)) {
        FixedRow(
            label = stringResource(R.string.vacuglide_send_stop),
            value = stringResource(R.string.vacuglide_always_on),
            description = stringResource(R.string.vacuglide_send_stop_desc),
            valueColor = colors.muted,
            isFirst = true,
        )
        LabelRow(
            label = stringResource(R.string.vacuglide_check_in),
            description = stringResource(R.string.vacuglide_check_in_desc),
            isFirst = false,
        ) {
            ValueMenu(
                options = listOf<Duration?>(null, 10.minutes, 15.minutes, 30.minutes),
                selected = settings.checkInEvery,
                label = { every ->
                    if (every == null) {
                        stringResource(R.string.off)
                    } else {
                        stringResource(R.string.check_in_every, every.inWholeMinutes.toInt())
                    }
                },
                onSelect = { onIntent(UserIntent.SetCheckInInterval(device.id, it)) },
            )
        }
        SettingRow(
            label = stringResource(R.string.vacuglide_keep_running),
            description = stringResource(R.string.vacuglide_keep_running_desc),
            value =
                stringResource(
                    if (isBatteryUnrestricted) R.string.battery_allowed
                    else R.string.battery_restricted
                ),
            valueColor = if (isBatteryUnrestricted) colors.ok else colors.warn,
            onClick = onOpenBatterySettings,
            isFirst = false,
        )
    }
    Card(caption = stringResource(R.string.vacuglide_output)) {
        FixedRow(
            label = stringResource(R.string.vacuglide_tempo_range),
            value =
                withMonoReadouts(
                    Words.range(settings.tempoRange),
                    Words.range(settings.tempoRange),
                ),
            description = stringResource(R.string.vacuglide_tempo_range_desc),
            valueColor = colors.text,
            isFirst = true,
        )
        ToggleRow(
            label = stringResource(R.string.vacuglide_valve),
            description = stringResource(R.string.vacuglide_valve_desc),
            isOn = settings.mayStepValve,
            onChange = { onIntent(UserIntent.SetValveSteps(device.id, it)) },
            isFirst = false,
        )
    }
}

/** Check and Test (or Connect while unlinked), with the note on what each does. */
@Composable
private fun MotionTest(device: DeviceSummary, onIntent: (UserIntent) -> Unit) {
    val isLinked =
        device.status == DeviceStatus.Connected ||
            device.status == DeviceStatus.Playing ||
            device.status == DeviceStatus.Paused
    if (!isLinked) {
        AppButton(
            stringResource(
                if (device.status == DeviceStatus.Lost) R.string.action_reconnect
                else R.string.action_connect
            ),
            { onIntent(UserIntent.Connect(device.id)) },
            kind = ButtonKind.Primary,
            isEnabled = device.status != DeviceStatus.Connecting,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    val isVacuGlide = device.settings is DeviceSettings.VacuGlide
    Column(verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.s)) {
        if (isVacuGlide) {
            AppButton(
                stringResource(R.string.vacuglide_test),
                { onIntent(UserIntent.TestMotion(device.id)) },
                kind = ButtonKind.Start,
                isEnabled = device.status == DeviceStatus.Connected,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(stringResource(R.string.vacuglide_test_desc))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.s)) {
                AppButton(
                    stringResource(R.string.device_check),
                    { onIntent(UserIntent.CheckConnection(device.id)) },
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    stringResource(R.string.device_test),
                    { onIntent(UserIntent.TestMotion(device.id)) },
                    kind = ButtonKind.Start,
                    isEnabled = device.status == DeviceStatus.Connected,
                    modifier = Modifier.weight(1f),
                )
            }
            Hint(stringResource(R.string.device_check_test_note))
        }
    }
}

/** Disconnect, when linked, and Remove, at the end of the page (board V-HandyPage). */
@Composable
private fun BottomActions(
    device: DeviceSummary,
    onIntent: (UserIntent) -> Unit,
    onBack: () -> Unit,
) {
    val isLinked =
        device.status == DeviceStatus.Connected ||
            device.status == DeviceStatus.Playing ||
            device.status == DeviceStatus.Paused
    Row(
        horizontalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.s),
        modifier = Modifier.padding(top = MagicHandyTheme.dimens.s),
    ) {
        if (isLinked) {
            AppButton(
                stringResource(R.string.action_disconnect),
                { onIntent(UserIntent.Disconnect(device.id)) },
                modifier = Modifier.weight(1f),
            )
        }
        AppButton(
            stringResource(
                if (device.settings is DeviceSettings.Handy) R.string.device_remove_handy
                else R.string.device_remove
            ),
            {
                onIntent(UserIntent.Remove(device.id))
                onBack()
            },
            kind = ButtonKind.Destructive,
            modifier = Modifier.weight(1f),
        )
    }
}

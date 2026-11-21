package com.magichandy.mobile.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceLimit
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.core.api.HandyProfile
import com.magichandy.mobile.core.api.StepDirection
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.FixedRow
import com.magichandy.mobile.ui.components.LabelRow
import com.magichandy.mobile.ui.components.StepperRow
import com.magichandy.mobile.ui.components.ValueMenu

@Composable
internal fun HandyModelRow(
    device: DeviceSummary,
    settings: DeviceSettings.Handy,
    onIntent: (UserIntent) -> Unit,
) {
    val current =
        HandyProfile.entries.firstOrNull { it.storedName == settings.model }
            ?: HandyProfile.Original
    val title = stringResource(R.string.handy_model)
    val detail = stringResource(R.string.handy_model_desc)
    if (device.status == DeviceStatus.Playing || device.status == DeviceStatus.Paused) {
        FixedRow(label = title, value = profileName(current), description = detail, isFirst = false)
    } else {
        LabelRow(label = title, description = detail, isFirst = false) {
            ValueMenu(
                options = HandyProfile.entries,
                selected = current,
                label = { profileName(it) },
                onSelect = { onIntent(UserIntent.SetHandyModel(device.id, it)) },
            )
        }
    }
}

@Composable
private fun profileName(profile: HandyProfile): String =
    stringResource(
        when (profile) {
            HandyProfile.Original -> R.string.model_handy_original
            HandyProfile.Standard -> R.string.model_handy_standard
            HandyProfile.Pro -> R.string.model_handy_pro
        }
    )

@Composable
internal fun DeviceLimitRow(
    id: DeviceId,
    limit: DeviceLimit,
    value: Int,
    range: IntRange,
    isFirst: Boolean,
    onIntent: (UserIntent) -> Unit,
) {
    val label =
        stringResource(
            when (limit) {
                DeviceLimit.VibrationMaximum -> R.string.vibrator_max_level
                DeviceLimit.TempoMinimum -> R.string.tempo_minimum
                DeviceLimit.TempoMaximum -> R.string.tempo_maximum
            }
        )
    StepperRow(
        label = label,
        value = stringResource(R.string.device_percent_value, value),
        downDescription = stringResource(R.string.device_decrease_limit, label),
        upDescription = stringResource(R.string.device_increase_limit, label),
        canDown = value > range.first,
        canUp = value < range.last,
        isFirst = isFirst,
        onDown = { onIntent(UserIntent.StepDeviceLimit(id, limit, StepDirection.Down)) },
        onUp = { onIntent(UserIntent.StepDeviceLimit(id, limit, StepDirection.Up)) },
    )
}

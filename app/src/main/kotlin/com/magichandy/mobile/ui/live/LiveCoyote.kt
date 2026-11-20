package com.magichandy.mobile.ui.live

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.CoyoteChannel
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceOutput
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.StepperRow
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * A playing Coyote's channel strengths (board V-CoyoteLive). Only the person changes them, one unit
 * per tap, up to the limits on the Coyote's page. Each step needs the strength the Coyote last
 * reported, so the steppers wait for its first report, and while motion is paused.
 */
@Composable
internal fun CoyoteStrength(
    device: DeviceSummary,
    phase: RunPhase,
    onStep: (device: DeviceId, channel: CoyoteChannel, increase: Boolean) -> Unit,
) {
    val limits = device.settings as? DeviceSettings.Coyote ?: return
    val reported = device.output as? DeviceOutput.Channels
    val canStep = reported != null && phase != RunPhase.Paused
    Card(caption = stringResource(R.string.live_coyote_caption, device.name)) {
        val channels =
            listOf(
                Triple(CoyoteChannel.A, reported?.strengthA, limits.maxStrengthA),
                Triple(CoyoteChannel.B, reported?.strengthB, limits.maxStrengthB),
            )
        channels.forEachIndexed { index, (channel, strength, limit) ->
            StepperRow(
                label = stringResource(R.string.coyote_channel, channel.name),
                value = strength?.toString() ?: stringResource(R.string.no_value),
                outOf = limit.toString(),
                downDescription = stringResource(R.string.coyote_strength_lower, channel.name),
                upDescription = stringResource(R.string.coyote_strength_raise, channel.name),
                onDown = { onStep(device.id, channel, false) },
                onUp = { onStep(device.id, channel, true) },
                isFirst = index == 0,
                canDown = canStep && strength != null && strength > 0,
                canUp = canStep && strength != null && strength < limit,
            )
        }
        val note =
            when {
                reported == null -> R.string.live_coyote_waiting
                phase == RunPhase.Paused -> R.string.live_coyote_paused
                limits.maxStrengthA == 0 && limits.maxStrengthB == 0 ->
                    R.string.live_coyote_no_limits
                else -> R.string.live_coyote_note
            }
        CardRow(isFirst = false) {
            Text(
                stringResource(note),
                style = MagicHandyTheme.type.small,
                color = MagicHandyTheme.colors.textSecondary,
                modifier = Modifier.weight(1f).padding(vertical = MagicHandyTheme.dimens.xs),
            )
        }
    }
}

package com.magichandy.mobile.ui.devices

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.CoyoteChannel
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.StepperRow
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** How far one tap moves a channel's limit, as approved with board V-CoyotePage. */
private const val LIMIT_STEP = 5

/** The top of the Coyote's own strength scale, for each channel. */
private const val COYOTE_SCALE = 200

/**
 * A Coyote's channel limits (board V-CoyotePage): the most each channel can reach. They change only
 * while the Coyote is disconnected and after its safety notes were read, and the core sends them on
 * its next connection.
 */
@Composable
internal fun CoyoteLimits(
    device: DeviceSummary,
    settings: DeviceSettings.Coyote,
    hasReadNotes: Boolean,
    onSet: (device: DeviceId, maximumA: Int, maximumB: Int) -> Unit,
) {
    // The core takes new limits only from a Coyote that isn't connected.
    val isDisconnected =
        device.status == DeviceStatus.NotConnected || device.status == DeviceStatus.NeedsSetup
    val canEdit = hasReadNotes && isDisconnected
    val a = settings.maxStrengthA
    val b = settings.maxStrengthB
    Card(caption = stringResource(R.string.coyote_limits)) {
        LimitRow(CoyoteChannel.A, a, canEdit, isFirst = true) { onSet(device.id, it, b) }
        LimitRow(CoyoteChannel.B, b, canEdit, isFirst = false) { onSet(device.id, a, it) }
        CardRow(isFirst = false) {
            Text(
                stringResource(R.string.coyote_limits_note),
                style = MagicHandyTheme.type.small,
                color = MagicHandyTheme.colors.textSecondary,
                modifier = Modifier.weight(1f).padding(vertical = MagicHandyTheme.dimens.xs),
            )
        }
    }
    Hint(
        stringResource(
            when {
                !hasReadNotes -> R.string.coyote_limits_notes
                isDisconnected -> R.string.coyote_limits_hint
                else -> R.string.coyote_limits_locked
            }
        )
    )
}

@Composable
private fun LimitRow(
    channel: CoyoteChannel,
    limit: Int,
    canEdit: Boolean,
    isFirst: Boolean,
    onChange: (Int) -> Unit,
) {
    StepperRow(
        label = stringResource(R.string.coyote_channel, channel.name),
        value = limit.toString(),
        downDescription = stringResource(R.string.coyote_limit_lower, channel.name),
        upDescription = stringResource(R.string.coyote_limit_raise, channel.name),
        onDown = { onChange((limit - LIMIT_STEP).coerceAtLeast(0)) },
        onUp = { onChange((limit + LIMIT_STEP).coerceAtMost(COYOTE_SCALE)) },
        isFirst = isFirst,
        canDown = canEdit && limit > 0,
        canUp = canEdit && limit < COYOTE_SCALE,
    )
}

package com.magichandy.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.BackendNotice
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.StatusDot
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Why the last action couldn't complete, above the footer and any Stop receipt. The core clears it
 * on the next action, so it needs no dismiss control.
 */
@Composable
fun BackendNoticeToast(notice: BackendNotice, modifier: Modifier = Modifier) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.card
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = MagicHandyTheme.dimens.m, vertical = MagicHandyTheme.dimens.cozy)
            .clip(shape)
            .background(colors.toast)
            .border(MagicHandyTheme.dimens.hairline, colors.warnLine, shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(
                horizontal = MagicHandyTheme.dimens.roomy,
                vertical = MagicHandyTheme.dimens.m,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(DotTone.Warn)
        Spacer(Modifier.width(MagicHandyTheme.dimens.s))
        Text(
            stringResource(
                when (notice) {
                    BackendNotice.FeatureUnavailable -> R.string.notice_feature_unavailable
                    BackendNotice.StorageUnavailable -> R.string.notice_storage_unavailable
                    BackendNotice.SettingsConflict -> R.string.notice_settings_conflict
                    BackendNotice.ReconcileDevices -> R.string.notice_reconcile_devices
                    BackendNotice.RunAlreadyActive -> R.string.notice_run_already_active
                }
            ),
            style = MagicHandyTheme.type.small,
        )
    }
}

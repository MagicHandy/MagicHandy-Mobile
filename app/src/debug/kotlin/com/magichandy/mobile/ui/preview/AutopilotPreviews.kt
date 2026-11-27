package com.magichandy.mobile.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.magichandy.mobile.core.api.AutopilotStatus
import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.ui.AppContent
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Chat while Autopilot holds because the planning model's sign-in expired: the banner names the
 * cause, and Try again plans once more.
 */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun ChatPlanningFailed(@PreviewParameter(PaletteProvider::class) palette: Palette) {
    val state =
        SampleStates.playing.let {
            it.copy(
                autopilot = it.autopilot.copy(status = AutopilotStatus.Held),
                run = it.run.copy(isPlanningHeld = true),
                banners =
                    listOf(
                        Banner.PlanningFailed(
                            ProviderReplyFailure(ProviderReplyFailure.Reason.SignInRequired)
                        )
                    ),
            )
        }
    MagicHandyTheme(palette) { AppContent(state, onIntent = {}, onStop = {}) }
}

package com.magichandy.mobile.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StartRefusal
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.Banner as BannerView
import com.magichandy.mobile.ui.components.BannerTone
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** Persistent notices above the transcript (board V-ConnectionLost). Each names one next step. */
@Composable
fun ChatBanners(
    state: AppState,
    onIntent: (UserIntent) -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.banners.isEmpty()) return
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = MagicHandyTheme.dimens.xxs, bottom = MagicHandyTheme.dimens.xs),
        verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.s),
    ) {
        for (banner in state.banners) {
            when (banner) {
                is Banner.DeviceLost -> DeviceLostBanner(state, banner, onIntent)
                Banner.PlanningHeld ->
                    BannerView(
                        tone = BannerTone.Warn,
                        title = stringResource(R.string.banner_held_title),
                        body =
                            stringResource(
                                if (state.run.phase == RunPhase.Playing) {
                                    R.string.banner_held_playing
                                } else {
                                    R.string.banner_held_stopped
                                }
                            ),
                    )
                is Banner.CheckInDue -> CheckInBanner(banner, onIntent)
                is Banner.StartRefused ->
                    StartRefusedBanner(banner.cause, onOpenDevices, onOpenSettings)
            }
        }
    }
}

@Composable
internal fun CheckInBanner(banner: Banner.CheckInDue, onIntent: (UserIntent) -> Unit) {
    BannerView(
        tone = BannerTone.Warn,
        title = stringResource(R.string.banner_check_in_title),
        body =
            stringResource(
                R.string.banner_check_in_body,
                Words.countdown(banner.answerWithin),
                banner.name,
            ),
        actionLabel = stringResource(R.string.banner_check_in_action),
        onAction = { onIntent(UserIntent.AnswerCheckIn(banner.device)) },
    )
}

@Composable
private fun DeviceLostBanner(
    state: AppState,
    banner: Banner.DeviceLost,
    onIntent: (UserIntent) -> Unit,
) {
    val title =
        stringResource(
            when (banner.connection) {
                ConnectionKind.Bluetooth -> R.string.banner_lost_bluetooth
                ConnectionKind.HandyCloud,
                ConnectionKind.AutoblowCloud -> R.string.banner_lost_cloud
                ConnectionKind.IntifaceOnPhone,
                ConnectionKind.IntifaceOnNetwork -> R.string.banner_lost_intiface
            },
            banner.name,
        )
    val confirmed =
        state.stopReceipt?.outcomes.orEmpty().filter {
            it.device != banner.device && it.outcome == StopOutcome.Confirmed
        }
    val body = buildList {
        confirmed.forEach { add(stringResource(R.string.banner_confirmed_stop, it.name)) }
        add(Words.afterLoss(banner.afterLoss, banner.name))
        add(stringResource(R.string.banner_reconnect_never_restarts))
    }
    BannerView(
        tone = BannerTone.Danger,
        title = title,
        body = body.reduce { joined, next -> stringResource(R.string.sentences, joined, next) },
        actionLabel = stringResource(R.string.banner_reconnect, banner.name),
        onAction = { onIntent(UserIntent.Connect(banner.device)) },
    )
}

@Composable
private fun StartRefusedBanner(
    cause: StartRefusal,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val title = stringResource(R.string.banner_refused_title)
    when (cause) {
        StartRefusal.JournalUnavailable ->
            BannerView(BannerTone.Warn, title, stringResource(R.string.banner_refused_journal))
        StartRefusal.NoDeviceInRun ->
            BannerView(
                BannerTone.Warn,
                title,
                stringResource(R.string.banner_refused_no_device),
                actionLabel = stringResource(R.string.banner_open_devices),
                onAction = onOpenDevices,
            )
        StartRefusal.MotionModeOff ->
            BannerView(
                BannerTone.Warn,
                title,
                stringResource(R.string.banner_refused_mode_off),
                actionLabel = stringResource(R.string.banner_open_settings),
                onAction = onOpenSettings,
            )
        StartRefusal.ServiceUnavailable ->
            BannerView(BannerTone.Warn, title, stringResource(R.string.banner_refused_service))
    }
}

package com.magichandy.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppLanguage
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.UpdateCheck
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.DiagnosticsShare
import com.magichandy.mobile.ui.NotificationsRefusedBanner
import com.magichandy.mobile.ui.applyLanguage
import com.magichandy.mobile.ui.components.Caption
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.PageHeader
import com.magichandy.mobile.ui.components.Segment
import com.magichandy.mobile.ui.components.Segmented
import com.magichandy.mobile.ui.components.SettingRow
import com.magichandy.mobile.ui.components.segmentedWidthFor
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * What Android allows the app, as the core last saw it (see `systemAccess`).
 *
 * @property areNotificationsRefused true when notifications are off because they were refused,
 *   rather than because the app hasn't asked yet. Only then do screens warn about it.
 */
data class SystemAccess(
    val areNotificationsAllowed: Boolean,
    val isBatteryUnrestricted: Boolean,
    val areNotificationsRefused: Boolean = !areNotificationsAllowed,
)

/**
 * System screens and requests App settings can open.
 *
 * @property requestStopTile asks Android to add the Stop tile to Quick Settings and reports what
 *   happened; null before Android 13, where only the person can add it.
 */
data class SystemLinks(
    val openNotificationSettings: () -> Unit,
    val openBatterySettings: () -> Unit,
    val requestStopTile: ((onResult: (StopTileRequest) -> Unit) -> Unit)? = null,
)

/** What Android said when asked to add the Stop tile. */
enum class StopTileRequest {
    /** The tile is in Quick Settings now, or already was. */
    Added,

    /** The person declined. */
    Declined,

    /** Android couldn't ask, so the person has to add the tile by hand. */
    Failed,
}

/**
 * App settings (board V-AppSettings): durable, app-wide preferences. Reached from the last row of
 * the compact settings sheet.
 */
@Composable
fun AppSettingsScreen(
    state: AppState,
    system: SystemAccess,
    links: SystemLinks,
    diagnostics: DiagnosticsShare,
    onIntent: (UserIntent) -> Unit,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = MagicHandyTheme.dimens
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = dimens.m, end = dimens.m, bottom = dimens.s),
        verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.snug),
    ) {
        PageHeader(
            title = stringResource(R.string.app_settings_title),
            backDescription = stringResource(R.string.back_to_settings),
            onBack = onBack,
            breadcrumb = stringResource(R.string.tab_settings),
        )
        if (system.areNotificationsRefused)
            NotificationsRefusedBanner(links.openNotificationSettings)
        Section(R.string.app_models) {
            val chat = state.models.connections.firstOrNull { it.id == state.models.chatConnection }
            SettingRow(
                label = stringResource(R.string.chat_model_manage),
                value =
                    pluralStringResource(
                        R.plurals.app_models_value,
                        state.models.connections.size,
                        chat?.shortName ?: stringResource(R.string.settings_none),
                        state.models.connections.size,
                    ),
                onClick = onOpenModels,
                isFirst = true,
            )
        }
        Section(R.string.app_appearance) {
            CardRow(isFirst = true) { PaletteChoice(state, onIntent, Modifier.weight(1f)) }
            LanguageRow(state.app.language, onIntent)
        }
        Section(R.string.app_safety) {
            SettingRow(
                label = stringResource(R.string.app_lock_screen_stop),
                value =
                    stringResource(
                        if (system.areNotificationsAllowed) R.string.on else R.string.off
                    ),
                valueColor =
                    if (system.areNotificationsAllowed) null else MagicHandyTheme.colors.warn,
                valueTone = if (system.areNotificationsAllowed) DotTone.Ok else DotTone.Warn,
                onClick = links.openNotificationSettings,
                isFirst = true,
            )
            SettingRow(
                label = stringResource(R.string.app_battery),
                value =
                    stringResource(
                        if (system.isBatteryUnrestricted) R.string.battery_unrestricted
                        else R.string.battery_optimized
                    ),
                valueTone = if (system.isBatteryUnrestricted) DotTone.Ok else DotTone.Warn,
                onClick = links.openBatterySettings,
                isFirst = false,
            )
            StopTileRow(links.requestStopTile, observed = state.app.isStopTileAdded)
        }
        Section(R.string.app_data) {
            // The report is built only when asked for, and its text never shows here.
            SettingRow(
                label = stringResource(R.string.app_diagnostics),
                description =
                    if (diagnostics.hasFailed) stringResource(R.string.diagnostics_failed)
                    else null,
                value =
                    stringResource(
                        if (diagnostics.isPreparing) R.string.diagnostics_preparing
                        else R.string.app_diagnostics_value
                    ),
                onClick = diagnostics::share,
                isFirst = true,
            )
            DeleteAllRow(onIntent)
        }
        Section(R.string.app_about) {
            SettingRow(
                label = stringResource(R.string.app_version, state.app.versionName),
                value = updateText(state.app.update) ?: stringResource(R.string.app_check_update),
                onClick = { onIntent(UserIntent.CheckForUpdate) },
                isFirst = true,
            )
        }
    }
}

@Composable
private fun Section(title: Int, rows: @Composable () -> Unit) {
    Caption(
        stringResource(title),
        Modifier.padding(
            start = MagicHandyTheme.dimens.xxs,
            top = MagicHandyTheme.dimens.s,
            bottom = MagicHandyTheme.dimens.xxs,
        ),
    )
    Card(contentPadding = PaddingValues(horizontal = MagicHandyTheme.dimens.m)) { rows() }
}

@Composable
private fun LanguageRow(language: AppLanguage, onIntent: (UserIntent) -> Unit) {
    val context = LocalContext.current
    var isOpen by rememberSaveable { mutableStateOf(false) }
    SettingRow(
        label = stringResource(R.string.app_language),
        value =
            if (language == AppLanguage.System)
                stringResource(
                    R.string.language_system_resolved,
                    stringResource(R.string.language_resolved),
                )
            else Words.language(language),
        onClick = { isOpen = !isOpen },
        isFirst = false,
    )
    if (isOpen) {
        AppLanguage.entries.forEach { option ->
            SettingRow(
                label = Words.language(option),
                value = if (option == language) stringResource(R.string.on) else null,
                onClick = {
                    onIntent(UserIntent.SetLanguage(option))
                    applyLanguage(context, option)
                    isOpen = false
                },
                isFirst = false,
            )
        }
    }
}

/** Deleting needs a second tap, because it can't be undone. */
@Composable
private fun DeleteAllRow(onIntent: (UserIntent) -> Unit) {
    var isArmed by rememberSaveable { mutableStateOf(false) }
    SettingRow(
        label = stringResource(R.string.app_delete_all),
        description = if (isArmed) stringResource(R.string.app_delete_confirm) else null,
        value = null,
        labelColor = MagicHandyTheme.colors.dangerText,
        onClick = {
            if (isArmed) {
                onIntent(UserIntent.DeleteAllChatsAndMemory)
                isArmed = false
            } else {
                isArmed = true
            }
        },
        isFirst = false,
    )
}

@Composable
private fun updateText(update: UpdateCheck): String? =
    when (update) {
        UpdateCheck.NotChecked -> null
        UpdateCheck.Checking -> stringResource(R.string.update_checking)
        UpdateCheck.UpToDate -> stringResource(R.string.update_up_to_date)
        is UpdateCheck.Available -> stringResource(R.string.update_available, update.version)
        UpdateCheck.Failed -> stringResource(R.string.update_failed)
    }

/** The palettes on offer, in order: all dark, each for a different screen or setting. */
private val OfferedPalettes = listOf(Palette.SteelAzure, Palette.OledBlack, Palette.HighContrast)

/**
 * "Palette" beside its three choices (board V-AppSettings), then a line on what the chosen one is
 * for. With large text the choices wrap under the label instead of squeezing it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteChoice(state: AppState, onIntent: (UserIntent) -> Unit, modifier: Modifier) {
    // The retired Lowlight shows as OLED black, which replaced it.
    val palette = state.app.palette.let { if (it == Palette.Lowlight) Palette.OledBlack else it }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.xs)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.cozy),
            verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.snug),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.app_palette), style = MagicHandyTheme.type.body)
            val segments = OfferedPalettes.map { Segment(it, Words.palette(it)) }
            // Wide enough for every name on one line; longer names move it under "Palette".
            val width =
                maxOf(
                    MagicHandyTheme.dimens.paletteWidth,
                    segmentedWidthFor(segments.map { it.label }),
                )
            Segmented(
                segments = segments,
                selected = palette,
                onSelect = { onIntent(UserIntent.SetPalette(it)) },
                modifier = Modifier.weight(1f).widthIn(min = width),
            )
        }
        Text(
            Words.paletteUse(palette),
            style = MagicHandyTheme.type.small,
            color = MagicHandyTheme.colors.textSecondary,
        )
    }
}

/**
 * Offers the Stop tile for Quick Settings. Android 13 and later add it on request; before that, the
 * row explains how to add it by hand.
 *
 * @param observed whether the tile is there, as the tile itself last reported; null until it has.
 */
@Composable
private fun StopTileRow(
    request: ((onResult: (StopTileRequest) -> Unit) -> Unit)?,
    observed: Boolean?,
) {
    var result by rememberSaveable { mutableStateOf<StopTileRequest?>(null) }
    // The tile's own report is the truth once there is one; until then, Android's answer.
    val isAdded = observed ?: (result == StopTileRequest.Added)
    SettingRow(
        label = stringResource(R.string.app_stop_tile),
        description =
            if (request == null || result == StopTileRequest.Failed)
                stringResource(R.string.stop_tile_by_hand)
            else null,
        value =
            when {
                isAdded -> stringResource(R.string.stop_tile_added)
                request != null -> stringResource(R.string.stop_tile_add)
                else -> null
            },
        valueTone = if (isAdded) DotTone.Ok else null,
        onClick = { request?.invoke { result = it } },
        isFirst = false,
    )
}

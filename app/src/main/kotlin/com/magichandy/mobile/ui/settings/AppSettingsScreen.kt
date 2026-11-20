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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppLanguage
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.UpdateCheck
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.NotificationsRefusedBanner
import com.magichandy.mobile.ui.components.Caption
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.FixedRow
import com.magichandy.mobile.ui.components.PageHeader
import com.magichandy.mobile.ui.components.Segment
import com.magichandy.mobile.ui.components.Segmented
import com.magichandy.mobile.ui.components.SettingRow
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

/** System screens App settings can open. */
data class SystemLinks(
    val openNotificationSettings: () -> Unit,
    val openBatterySettings: () -> Unit,
)

/**
 * App settings (board V-AppSettings): durable, app-wide preferences. Reached from the last row of
 * the compact settings sheet.
 */
@Composable
fun AppSettingsScreen(
    state: AppState,
    system: SystemAccess,
    links: SystemLinks,
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
            // TODO: add a row that offers the Quick Settings tile, and a diagnostics row once the
            // core exposes the redacted export.
        }
        Section(R.string.app_data) { DeleteAllRow(onIntent) }
        Section(R.string.app_about) {
            FixedRow(
                label = stringResource(R.string.app_version),
                value = state.app.versionName,
                isFirst = true,
            )
            SettingRow(
                label = stringResource(R.string.app_check_update),
                value = updateText(state.app.update),
                onClick = { onIntent(UserIntent.CheckForUpdate) },
                isFirst = false,
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
    var isOpen by rememberSaveable { mutableStateOf(false) }
    SettingRow(
        label = stringResource(R.string.app_language),
        value = Words.language(language),
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
        isFirst = true,
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

/**
 * "Palette" beside its three choices (board V-AppSettings). With large text the choices wrap under
 * the label instead of squeezing it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteChoice(state: AppState, onIntent: (UserIntent) -> Unit, modifier: Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.cozy),
        verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.snug),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.app_palette), style = MagicHandyTheme.type.body)
        Segmented(
            segments = Palette.entries.map { Segment(it, Words.palette(it)) },
            selected = state.app.palette,
            onSelect = { onIntent(UserIntent.SetPalette(it)) },
            modifier = Modifier.weight(1f).widthIn(min = MagicHandyTheme.dimens.paletteWidth),
        )
    }
}

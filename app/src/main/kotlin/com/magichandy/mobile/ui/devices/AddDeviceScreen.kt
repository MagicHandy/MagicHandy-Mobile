package com.magichandy.mobile.ui.devices

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.CoreCalls
import com.magichandy.mobile.ui.FormRefusal
import com.magichandy.mobile.ui.components.AnchoredColumn
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.Caption
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.IconAction
import com.magichandy.mobile.ui.components.PageHeader
import com.magichandy.mobile.ui.components.PushDown
import com.magichandy.mobile.ui.components.RadioMark
import com.magichandy.mobile.ui.components.TextInput
import com.magichandy.mobile.ui.components.topHairline
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.rememberFormSubmission
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Setup for one device family (boards V-AddHandy, V-AddHandyIntiface, V-Coyote and V-AddVibrator).
 * Connecting ends at Connected and idle; it never starts motion. A form stays open until the core
 * has saved the device, and [onDone] then returns to Devices.
 *
 * @param calls asks an Intiface bridge what it has, without moving anything, and submits forms.
 */
@Composable
fun AddDeviceScreen(
    family: DeviceFamily,
    state: AppState,
    onIntent: (UserIntent) -> Unit,
    calls: CoreCalls,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = MagicHandyTheme.dimens
    // The main action sits just above Stop when there's room, as on the boards.
    AnchoredColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dimens.m, end = dimens.m, bottom = dimens.s),
        verticalArrangement = Arrangement.spacedBy(dimens.cozy),
    ) {
        when (family) {
            DeviceFamily.Coyote ->
                SetupHeader(family, stringResource(R.string.add_coyote_before), onBack)
            DeviceFamily.Vibrator ->
                SetupHeader(family, stringResource(R.string.add_vibrator_title), onBack)
            DeviceFamily.LinearActuator ->
                SetupHeader(family, stringResource(R.string.add_linear_title), onBack)
            DeviceFamily.Handy,
            DeviceFamily.AutoblowUltra,
            DeviceFamily.VacuGlide ->
                PageHeader(
                    title =
                        if (family == DeviceFamily.Handy) stringResource(R.string.add_handy_title)
                        else stringResource(R.string.add_autoblow_title, Words.family(family)),
                    backDescription = stringResource(R.string.back_to_devices),
                    onBack = onBack,
                    breadcrumb = stringResource(R.string.add_breadcrumb),
                )
        }
        when (family) {
            DeviceFamily.Handy -> AddHandy(state, onIntent, calls, onDone)
            DeviceFamily.LinearActuator -> AddStroker(calls, onDone)
            DeviceFamily.Coyote -> AddCoyote(state, onIntent, onDone)
            DeviceFamily.Vibrator -> AddVibrator(calls, onDone)
            DeviceFamily.AutoblowUltra,
            DeviceFamily.VacuGlide -> AddAutoblow(family, calls, onDone)
        }
    }
}

/**
 * Back with the "Devices › Add" crumb, then the family's tile, its name and [subtitle] (boards
 * V-Coyote and V-AddVibrator).
 */
@Composable
private fun SetupHeader(family: DeviceFamily, subtitle: String, onBack: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(
                AppIcons.Back,
                stringResource(R.string.back_to_devices),
                onBack,
                Modifier.offset(x = -MagicHandyTheme.dimens.backOffset),
            )
            Text(
                stringResource(R.string.add_breadcrumb),
                style = MagicHandyTheme.type.small,
                color = MagicHandyTheme.colors.muted,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = MagicHandyTheme.dimens.xxs),
        ) {
            DeviceTile(familyIcon(family), size = MagicHandyTheme.dimens.pageTile)
            Column(Modifier.padding(start = MagicHandyTheme.dimens.m)) {
                Text(
                    Words.family(family),
                    style = MagicHandyTheme.type.deviceName,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    subtitle,
                    style = MagicHandyTheme.type.small,
                    color = MagicHandyTheme.colors.muted,
                )
            }
        }
    }
}

@Composable
internal fun ColumnScope.AddHandy(
    state: AppState,
    onIntent: (UserIntent) -> Unit,
    calls: CoreCalls,
    onDone: () -> Unit,
) {
    var method by rememberSaveable { mutableStateOf(HandyMethod.Bluetooth) }
    Caption(stringResource(R.string.add_handy_how))
    Card(contentPadding = PaddingValues(), modifier = Modifier.selectableGroup()) {
        HandyMethod.entries.forEachIndexed { index, option ->
            MethodOption(
                title = stringResource(option.title),
                description = stringResource(option.description),
                isSelected = method == option,
                isFirst = index == 0,
                onSelect = { method = option },
            )
        }
    }
    when (method) {
        HandyMethod.Bluetooth -> {
            ScanSection(
                DeviceFamily.Handy,
                state,
                onIntent,
                onDone,
                defaultName = Words.family(DeviceFamily.Handy),
            )
            Hint(stringResource(R.string.add_handy_note))
        }
        HandyMethod.Cloud -> CloudHandyForm(calls, onDone)
        HandyMethod.Intiface -> AddStroker(calls, onDone)
    }
}

/** The ways to reach The Handy (board V-AddHandy; Intiface Central from V-AddHandyIntiface). */
private enum class HandyMethod(
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
) {
    Bluetooth(R.string.add_handy_bluetooth, R.string.add_handy_bluetooth_desc),
    Cloud(R.string.add_handy_cloud, R.string.add_handy_cloud_desc),
    Intiface(R.string.add_handy_intiface, R.string.add_handy_intiface_desc),
}

/** A radio row inside the method card; the selected one is raised (board V-AddHandy). */
@Composable
private fun MethodOption(
    title: String,
    description: String,
    isSelected: Boolean,
    isFirst: Boolean,
    onSelect: () -> Unit,
) {
    val colors = MagicHandyTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (isFirst) Modifier else Modifier.topHairline(colors.line))
            .background(if (isSelected) colors.raised else colors.surface)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect)
            .padding(
                horizontal = MagicHandyTheme.dimens.roomy,
                vertical = MagicHandyTheme.dimens.m,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioMark(isSelected)
        Column(Modifier.padding(start = MagicHandyTheme.dimens.roomy).weight(1f)) {
            Text(title, style = MagicHandyTheme.type.bodyStrong)
            Text(description, style = MagicHandyTheme.type.small, color = colors.textSecondary)
        }
    }
}

@Composable
private fun ColumnScope.CloudHandyForm(calls: CoreCalls, onDone: () -> Unit) {
    val defaultName = Words.family(DeviceFamily.Handy)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    // The key is never saved in instance state, so it can't outlive this screen.
    var key by remember { mutableStateOf("") }
    val submission = rememberFormSubmission(calls.submit)
    TextInput(
        name,
        {
            name = it
            submission.edited()
        },
        stringResource(R.string.field_name),
    )
    TextInput(
        key,
        {
            key = it
            submission.edited()
        },
        stringResource(R.string.field_connection_key),
        placeholder = stringResource(R.string.field_connection_key_hint),
        isSecret = true,
    )
    PushDown()
    FormRefusal(submission)
    AppButton(
        stringResource(
            if (submission.isSending) R.string.form_saving else R.string.add_cloud_connect
        ),
        { submission.send(UserIntent.AddCloudHandy(name.trim(), Secret(key.trim())), onDone) },
        kind = ButtonKind.Primary,
        isEnabled = name.isNotBlank() && key.isNotBlank() && !submission.isSending,
        modifier = Modifier.fillMaxWidth(),
    )
}

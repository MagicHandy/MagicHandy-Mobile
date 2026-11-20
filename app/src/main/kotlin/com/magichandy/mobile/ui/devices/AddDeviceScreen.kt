package com.magichandy.mobile.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
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
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.Caption
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.PageHeader
import com.magichandy.mobile.ui.components.RadioMark
import com.magichandy.mobile.ui.components.TextInput
import com.magichandy.mobile.ui.components.topHairline
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Setup for one device family (boards V-AddHandy and V-Coyote). Connecting ends at Connected and
 * idle; it never starts motion. [onDone] returns to Devices once the device is added.
 */
@Composable
fun AddDeviceScreen(
    family: DeviceFamily,
    state: AppState,
    onIntent: (UserIntent) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
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
        val title =
            when (family) {
                DeviceFamily.Handy -> stringResource(R.string.add_handy_title)
                DeviceFamily.Vibrator -> stringResource(R.string.add_vibrator_title)
                DeviceFamily.Coyote -> Words.family(family)
                DeviceFamily.AutoblowUltra,
                DeviceFamily.VacuGlide ->
                    stringResource(R.string.add_autoblow_title, Words.family(family))
            }
        PageHeader(
            title = title,
            backDescription = stringResource(R.string.back_to_devices),
            onBack = onBack,
            breadcrumb = stringResource(R.string.add_breadcrumb),
        )
        when (family) {
            DeviceFamily.Handy -> AddHandy(state, onIntent, onDone)
            DeviceFamily.Coyote -> AddCoyote(state, onIntent, onDone)
            DeviceFamily.Vibrator -> AddVibrator(onIntent, onDone)
            DeviceFamily.AutoblowUltra,
            DeviceFamily.VacuGlide -> AddAutoblow(family, onIntent, onDone)
        }
    }
}

@Composable
internal fun AddHandy(state: AppState, onIntent: (UserIntent) -> Unit, onDone: () -> Unit) {
    var useCloud by rememberSaveable { mutableStateOf(false) }
    Caption(stringResource(R.string.add_handy_how))
    Card(contentPadding = PaddingValues(), modifier = Modifier.selectableGroup()) {
        MethodOption(
            title = stringResource(R.string.add_handy_bluetooth),
            description = stringResource(R.string.add_handy_bluetooth_desc),
            isSelected = !useCloud,
            isFirst = true,
            onSelect = { useCloud = false },
        )
        MethodOption(
            title = stringResource(R.string.add_handy_cloud),
            description = stringResource(R.string.add_handy_cloud_desc),
            isSelected = useCloud,
            isFirst = false,
            onSelect = { useCloud = true },
        )
    }
    if (useCloud) {
        CloudHandyForm(onIntent, onDone)
    } else {
        ScanSection(
            DeviceFamily.Handy,
            state,
            onIntent,
            onDone,
            defaultName = Words.family(DeviceFamily.Handy),
        )
        Hint(stringResource(R.string.add_handy_note))
    }
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
private fun CloudHandyForm(onIntent: (UserIntent) -> Unit, onDone: () -> Unit) {
    val defaultName = Words.family(DeviceFamily.Handy)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    // The key is never saved in instance state, so it can't outlive this screen.
    var key by remember { mutableStateOf("") }
    TextInput(name, { name = it }, stringResource(R.string.field_name))
    TextInput(
        key,
        { key = it },
        stringResource(R.string.field_connection_key),
        placeholder = stringResource(R.string.field_connection_key_hint),
        isSecret = true,
    )
    AppButton(
        stringResource(R.string.add_cloud_connect),
        {
            onIntent(UserIntent.AddCloudHandy(name.trim(), Secret(key.trim())))
            onDone()
        },
        kind = ButtonKind.Primary,
        isEnabled = name.isNotBlank() && key.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    )
}

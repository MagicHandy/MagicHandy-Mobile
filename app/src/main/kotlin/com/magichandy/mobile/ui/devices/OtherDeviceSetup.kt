package com.magichandy.mobile.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.Banner
import com.magichandy.mobile.ui.components.BannerTone
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.TextInput
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.theme.MagicHandyTheme

@Composable
internal fun AddCoyote(state: AppState, onIntent: (UserIntent) -> Unit, onDone: () -> Unit) {
    val colors = MagicHandyTheme.colors
    val acknowledged = state.devices.hasAcknowledgedCoyoteNotes
    // Once the notes are acknowledged, the box stays ticked and locked.
    var isTicked by rememberSaveable { mutableStateOf(false) }
    val isChecked = acknowledged || isTicked
    var isScanning by rememberSaveable { mutableStateOf(false) }
    Card(
        caption = stringResource(R.string.add_coyote_before),
        contentPadding =
            PaddingValues(
                start = MagicHandyTheme.dimens.m,
                end = MagicHandyTheme.dimens.m,
                bottom = MagicHandyTheme.dimens.s,
            ),
    ) {
        val notes =
            listOf(
                R.string.add_coyote_note_1,
                R.string.add_coyote_note_2,
                R.string.add_coyote_note_3,
                R.string.add_coyote_note_4,
            )
        notes.forEachIndexed { index, note ->
            Row(Modifier.padding(vertical = MagicHandyTheme.dimens.noteInset)) {
                Text(
                    (index + 1).toString(),
                    style = MagicHandyTheme.type.label,
                    color = colors.accent,
                    modifier = Modifier.padding(end = MagicHandyTheme.dimens.cozy),
                )
                Text(
                    stringResource(note),
                    style = MagicHandyTheme.type.small,
                    color = colors.textSecondary,
                )
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = MagicHandyTheme.dimens.touch).toggleable(
            value = isChecked,
            enabled = !acknowledged,
            role = Role.Checkbox,
        ) {
            isTicked = it
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(isChecked)
        Text(
            stringResource(R.string.add_coyote_ack),
            style = MagicHandyTheme.type.body,
            modifier = Modifier.padding(start = MagicHandyTheme.dimens.m),
        )
    }
    Hint(stringResource(R.string.add_coyote_hint))
    if (isScanning) {
        ScanSection(
            DeviceFamily.Coyote,
            state,
            onIntent,
            onDone,
            defaultName = Words.family(DeviceFamily.Coyote),
        )
    } else {
        AppButton(
            stringResource(R.string.add_coyote_find),
            {
                if (!acknowledged) onIntent(UserIntent.AcknowledgeCoyoteNotes)
                isScanning = true
            },
            kind = ButtonKind.Primary,
            isEnabled = isChecked,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A checkbox drawing. The row it sits in carries the checkbox semantics. */
@Composable
internal fun CheckBox(isChecked: Boolean) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.small
    Box(
        Modifier.size(MagicHandyTheme.dimens.checkBox)
            .clip(shape)
            .background(if (isChecked) colors.accent else colors.well)
            .border(
                MagicHandyTheme.dimens.hairline,
                if (isChecked) colors.accent else colors.fieldLine,
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isChecked)
            Icon(
                AppIcons.Check,
                null,
                tint = colors.onAccent,
                modifier = Modifier.size(MagicHandyTheme.dimens.smallIcon),
            )
    }
}

@Composable
internal fun AddVibrator(onIntent: (UserIntent) -> Unit, onDone: () -> Unit) {
    var address by rememberSaveable { mutableStateOf(DEFAULT_INTIFACE_ADDRESS) }
    Text(
        stringResource(R.string.add_vibrator_desc),
        style = MagicHandyTheme.type.small,
        color = MagicHandyTheme.colors.textSecondary,
    )
    TextInput(
        address,
        { address = it },
        stringResource(R.string.field_intiface_address),
        placeholder = stringResource(R.string.field_intiface_address_hint),
        keyboardType = KeyboardType.Uri,
    )
    AppButton(
        stringResource(R.string.add_vibrator_connect),
        {
            onIntent(UserIntent.AddIntiface(address.trim()))
            onDone()
        },
        kind = ButtonKind.Primary,
        isEnabled = address.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun AddAutoblow(family: DeviceFamily, onIntent: (UserIntent) -> Unit, onDone: () -> Unit) {
    val defaultName = Words.family(family)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var token by remember { mutableStateOf("") }
    if (family == DeviceFamily.VacuGlide) {
        Banner(
            tone = BannerTone.Danger,
            title = stringResource(R.string.vacuglide_warning_title),
            body = stringResource(R.string.vacuglide_warning_body),
            hasIcon = true,
        )
    }
    Text(
        stringResource(R.string.add_autoblow_desc),
        style = MagicHandyTheme.type.small,
        color = MagicHandyTheme.colors.textSecondary,
    )
    TextInput(name, { name = it }, stringResource(R.string.field_name))
    TextInput(
        token,
        { token = it },
        stringResource(R.string.field_device_token),
        placeholder = stringResource(R.string.field_device_token_hint),
        isSecret = true,
    )
    AppButton(
        stringResource(R.string.action_connect),
        {
            onIntent(UserIntent.AddAutoblowDevice(family, name.trim(), Secret(token.trim())))
            onDone()
        },
        kind = ButtonKind.Primary,
        isEnabled = name.isNotBlank() && token.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val DEFAULT_INTIFACE_ADDRESS = "127.0.0.1:12345"

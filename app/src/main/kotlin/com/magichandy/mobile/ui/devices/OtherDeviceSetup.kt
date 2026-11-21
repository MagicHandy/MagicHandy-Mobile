package com.magichandy.mobile.ui.devices

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.magichandy.mobile.core.api.BridgeDiscovery
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.FormIntent
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.CoreCalls
import com.magichandy.mobile.ui.FormRefusal
import com.magichandy.mobile.ui.FormSubmission
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.Banner
import com.magichandy.mobile.ui.components.BannerTone
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.PushDown
import com.magichandy.mobile.ui.components.TextInput
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.rememberFormSubmission
import com.magichandy.mobile.ui.settings.ChoiceList
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun ColumnScope.AddCoyote(
    state: AppState,
    onIntent: (UserIntent) -> Unit,
    onDone: () -> Unit,
) {
    val colors = MagicHandyTheme.colors
    val acknowledged = state.devices.hasAcknowledgedCoyoteNotes
    // Once the notes are acknowledged, the box stays ticked and locked.
    var isTicked by rememberSaveable { mutableStateOf(false) }
    val isChecked = acknowledged || isTicked
    var isScanning by rememberSaveable { mutableStateOf(false) }
    Card(
        contentPadding =
            PaddingValues(
                horizontal = MagicHandyTheme.dimens.m,
                vertical = MagicHandyTheme.dimens.s,
            )
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
        PushDown()
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

/** How far finding the devices at an Intiface address has got. */
internal sealed interface BridgeSearch<out T> {
    data object NotStarted : BridgeSearch<Nothing>

    data object Finding : BridgeSearch<Nothing>

    data class Failed(val reason: FormReason) : BridgeSearch<Nothing>

    /** What the reachable bridge at [address] reported. */
    data class Found<T>(val address: String, val choices: List<T>) : BridgeSearch<T>
}

/** The words for one kind of device behind Intiface, so vibrators and strokers share a setup. */
internal class BridgeWords(
    @param:StringRes val lead: Int,
    @param:StringRes val find: Int,
    @param:StringRes val finding: Int,
    @param:StringRes val findAgain: Int,
    @param:StringRes val noneTitle: Int,
    @param:StringRes val noneBody: Int,
    @param:StringRes val hint: Int,
    @param:StringRes val connect: Int,
)

internal val VibratorWords =
    BridgeWords(
        lead = R.string.add_vibrator_desc,
        find = R.string.vibrator_find,
        finding = R.string.vibrator_finding,
        findAgain = R.string.vibrator_find_again,
        noneTitle = R.string.vibrator_none_title,
        noneBody = R.string.vibrator_none_body,
        hint = R.string.vibrator_find_hint,
        connect = R.string.vibrator_connect,
    )

internal val StrokerWords =
    BridgeWords(
        lead = R.string.add_linear_desc,
        find = R.string.linear_find,
        finding = R.string.linear_finding,
        findAgain = R.string.linear_find_again,
        noneTitle = R.string.linear_none_title,
        noneBody = R.string.linear_none_body,
        hint = R.string.linear_find_hint,
        connect = R.string.vibrator_connect,
    )

/** Adds a vibrator through Intiface Central (board V-AddVibrator). */
@Composable
internal fun ColumnScope.AddVibrator(
    calls: CoreCalls,
    onDone: () -> Unit,
    replaceDevice: DeviceId? = null,
) {
    AddBridgeDevice(
        words = VibratorWords,
        initialAddress =
            replaceDevice?.let(calls.bridgeAddressForSetup) ?: DEFAULT_INTIFACE_ADDRESS,
        find = calls.findVibrators,
        name = { it.name },
        detail = { stringResource(R.string.vibrator_device, it.deviceIndex) },
        form = { address, choice ->
            UserIntent.AddIntiface(address, choice.deviceIndex, choice.selection, replaceDevice)
        },
        submission = rememberFormSubmission(calls.submit),
        onDone = onDone,
    )
}

/**
 * Adds The Handy, or another stroker, through Intiface Central (board V-AddHandyIntiface). The core
 * names The Handy by what Intiface reports, so one picker serves both. Intiface answers Stop for
 * the device, so the caveat comes first.
 */
@Composable
internal fun ColumnScope.AddStroker(
    calls: CoreCalls,
    onDone: () -> Unit,
    replaceDevice: DeviceId? = null,
) {
    IntifaceStopCaveat()
    AddBridgeDevice(
        words = StrokerWords,
        initialAddress =
            replaceDevice?.let(calls.bridgeAddressForSetup) ?: DEFAULT_INTIFACE_ADDRESS,
        find = calls.findLinearActuators,
        name = { it.name },
        detail = { stringResource(R.string.linear_device, it.deviceIndex, it.actuatorIndex) },
        form = { address, choice ->
            UserIntent.AddIntifaceLinear(
                address,
                choice.deviceIndex,
                choice.actuatorIndex,
                choice.selection,
                replaceDevice,
            )
        },
        submission = rememberFormSubmission(calls.submit),
        onDone = onDone,
    )
}

/** Intiface acknowledges Stop on the device's behalf, which doesn't prove it stopped. */
@Composable
internal fun IntifaceStopCaveat() {
    Banner(
        tone = BannerTone.Warn,
        title = stringResource(R.string.intiface_caveat_title),
        body = stringResource(R.string.intiface_caveat_body),
    )
}

/**
 * Finds the devices of one kind at an Intiface address, then connects the one picked; a bridge with
 * one has it picked already. Finding only asks the bridge and never moves anything. The form stays
 * open until the core has saved the device, and says why if it can't.
 */
@Composable
private fun <T> ColumnScope.AddBridgeDevice(
    initialAddress: String,
    words: BridgeWords,
    find: suspend (address: String) -> BridgeDiscovery<T>,
    name: (T) -> String,
    detail: @Composable (T) -> String,
    form: (address: String, choice: T) -> FormIntent,
    submission: FormSubmission,
    onDone: () -> Unit,
) {
    var address by rememberSaveable { mutableStateOf(initialAddress) }
    var search by remember { mutableStateOf<BridgeSearch<T>>(BridgeSearch.NotStarted) }
    var lookup by remember { mutableStateOf<Job?>(null) }
    var selected by remember { mutableStateOf<T?>(null) }
    val scope = rememberCoroutineScope()
    BridgeSetup(
        words = words,
        address = address,
        search = search,
        selected = selected,
        name = name,
        detail = detail,
        submission = submission,
        onAddressChange = {
            lookup?.cancel()
            address = it
            search = BridgeSearch.NotStarted
            selected = null
            submission.edited()
        },
        onFind = {
            val target = address.trim()
            search = BridgeSearch.Finding
            selected = null
            submission.edited()
            lookup?.cancel()
            lookup = scope.launch {
                val result = find(target)
                ensureActive()
                when (result) {
                    is BridgeDiscovery.Found -> {
                        search = BridgeSearch.Found(target, result.choices)
                        selected = result.choices.singleOrNull()
                    }
                    is BridgeDiscovery.Failed -> search = BridgeSearch.Failed(result.reason)
                }
            }
        },
        onSelect = {
            selected = it
            submission.edited()
        },
        onConnect = { found, choice -> submission.send(form(found.address, choice), onDone) },
    )
}

/** The stateless content of [AddBridgeDevice], for a given [search] and choice. */
@Composable
internal fun <T> ColumnScope.BridgeSetup(
    words: BridgeWords,
    address: String,
    search: BridgeSearch<T>,
    selected: T?,
    name: (T) -> String,
    detail: @Composable (T) -> String,
    submission: FormSubmission,
    onAddressChange: (String) -> Unit,
    onFind: () -> Unit,
    onSelect: (T) -> Unit,
    onConnect: (BridgeSearch.Found<T>, T) -> Unit,
) {
    Text(
        stringResource(words.lead),
        style = MagicHandyTheme.type.small,
        color = MagicHandyTheme.colors.textSecondary,
    )
    TextInput(
        address,
        onAddressChange,
        stringResource(R.string.field_intiface_address),
        placeholder = stringResource(R.string.field_intiface_address_hint),
        keyboardType = KeyboardType.Uri,
    )
    val found = search as? BridgeSearch.Found<T>
    AppButton(
        stringResource(
            when (search) {
                BridgeSearch.NotStarted -> words.find
                BridgeSearch.Finding -> words.finding
                is BridgeSearch.Found,
                is BridgeSearch.Failed -> words.findAgain
            }
        ),
        onFind,
        kind = if (found == null) ButtonKind.Primary else ButtonKind.Outline,
        isEnabled = address.isNotBlank() && search != BridgeSearch.Finding,
        modifier = Modifier.fillMaxWidth(),
    )
    if (search is BridgeSearch.Failed) Hint(Words.formReason(search.reason))
    if (found != null && found.choices.isEmpty()) {
        Banner(
            tone = BannerTone.Warn,
            title = stringResource(words.noneTitle),
            body = stringResource(words.noneBody),
        )
    } else if (found != null) {
        Card(caption = stringResource(R.string.vibrator_found, found.choices.size)) {
            ChoiceList(
                options = found.choices,
                selected = selected,
                label = { name(it) },
                description = detail,
                onSelect = onSelect,
            )
        }
    }
    Hint(stringResource(words.hint))
    if (found != null && found.choices.isNotEmpty()) {
        PushDown()
        FormRefusal(submission)
        AppButton(
            when {
                submission.isSending -> stringResource(R.string.form_saving)
                selected != null -> stringResource(words.connect, name(selected))
                else -> stringResource(R.string.action_connect)
            },
            { selected?.let { onConnect(found, it) } },
            kind = ButtonKind.Primary,
            isEnabled = selected != null && !submission.isSending,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ColumnScope.AddAutoblow(family: DeviceFamily, calls: CoreCalls, onDone: () -> Unit) {
    val defaultName = Words.family(family)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var token by remember { mutableStateOf("") }
    val submission = rememberFormSubmission(calls.submit)
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
    TextInput(
        name,
        {
            name = it
            submission.edited()
        },
        stringResource(R.string.field_name),
    )
    TextInput(
        token,
        {
            token = it
            submission.edited()
        },
        stringResource(R.string.field_device_token),
        placeholder = stringResource(R.string.field_device_token_hint),
        isSecret = true,
    )
    PushDown()
    FormRefusal(submission)
    AppButton(
        stringResource(if (submission.isSending) R.string.form_saving else R.string.action_connect),
        {
            submission.send(
                UserIntent.AddAutoblowDevice(
                    family,
                    name.trim(),
                    Secret(token.trim()),
                    connectAfterSave = true,
                ),
                onDone,
            )
        },
        kind = ButtonKind.Primary,
        isEnabled = name.isNotBlank() && token.isNotBlank() && !submission.isSending,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val DEFAULT_INTIFACE_ADDRESS = "127.0.0.1:12345"

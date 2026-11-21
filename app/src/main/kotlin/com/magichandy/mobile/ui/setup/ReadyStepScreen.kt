package com.magichandy.mobile.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.PushDown
import com.magichandy.mobile.ui.format.PersonaWords
import com.magichandy.mobile.ui.settings.chatConnection

/** Where You're set can go next. */
data class ReadyExits(
    val back: () -> Unit,
    val chooseAi: () -> Unit,
    val companion: () -> Unit,
    val addDevice: () -> Unit,
    val finish: () -> Unit,
)

/**
 * Setup's last step (boards V-SetupReady and V-SetupReadyNoModel): what's chosen, what's left, and
 * the way into the app. Without a model the person can still finish and explore.
 */
@Composable
fun ReadyStepScreen(state: AppState, exits: ReadyExits, modifier: Modifier = Modifier) {
    val connection = chatConnection(state)
    val persona = state.settings.persona
    val devices = state.devices.devices
    val separator = stringResource(R.string.separator)
    SetupPage(
        step = 4,
        title = stringResource(R.string.ready_title),
        lead = stringResource(R.string.ready_lead),
        onBack = exits.back,
        backTo = stringResource(R.string.companion_title),
        modifier = modifier,
    ) {
        Card {
            if (connection != null) {
                ReadyRow(
                    label = stringResource(R.string.ready_model),
                    value =
                        stringResource(
                            R.string.ready_model_value,
                            connection.name,
                            connection.model,
                        ),
                    isDone = true,
                    isFirst = true,
                    onClick = exits.chooseAi,
                )
            } else {
                ReadyRow(
                    label = stringResource(R.string.ready_model),
                    value = stringResource(R.string.ready_no_model),
                    isDone = false,
                    isFirst = true,
                    onClick = null,
                    action = stringResource(R.string.ready_choose_ai),
                    onAction = exits.chooseAi,
                )
            }
            ReadyRow(
                label = stringResource(R.string.ready_companion),
                value =
                    stringResource(
                        R.string.persona_line,
                        persona.name,
                        PersonaWords.line(persona.personality),
                    ),
                isDone = true,
                isFirst = false,
                onClick = exits.companion,
            )
            if (devices.isEmpty()) {
                ReadyRow(
                    label = stringResource(R.string.ready_device),
                    value = stringResource(R.string.ready_device_optional),
                    isDone = false,
                    isFirst = false,
                    onClick = null,
                    action = stringResource(R.string.ready_add_device),
                    onAction = exits.addDevice,
                )
            } else {
                ReadyRow(
                    label = stringResource(R.string.ready_device),
                    value = devices.joinToString(separator) { it.name },
                    isDone = true,
                    isFirst = false,
                    onClick = null,
                )
            }
        }
        Hint(stringResource(R.string.ready_device_note))
        PushDown()
        Hint(
            stringResource(
                if (connection != null) R.string.ready_privacy else R.string.ready_explore
            )
        )
        AppButton(
            stringResource(
                if (connection != null) R.string.onboarding_start else R.string.ready_finish
            ),
            exits.finish,
            kind = ButtonKind.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

package com.magichandy.mobile.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.magichandy.mobile.R
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.BrandMark
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.devices.CheckBox
import com.magichandy.mobile.ui.setup.SETUP_STEPS
import com.magichandy.mobile.ui.setup.SetupHeader
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Before you start (board V-Onboarding), the first step of setup: the adults-only acknowledgement
 * and a real Stop rehearsal with nothing connected. Continue sits just above Stop, so the
 * rehearsal's receipt appears between them. Permissions are asked for later, where they're needed.
 *
 * @param hasAcknowledged true when Back returns here from Choose your AI. The acknowledgement is
 *   already recorded, so the box stays ticked and Continue simply goes forward again.
 */
@Composable
fun BeforeYouStartScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    hasAcknowledged: Boolean = false,
) {
    var isAdult by rememberSaveable { mutableStateOf(hasAcknowledged) }
    val colors = MagicHandyTheme.colors
    val title = stringResource(R.string.onboarding_title)
    val pageTitle = stringResource(R.string.setup_page, title, 1, SETUP_STEPS)
    Column(
        modifier
            .fillMaxSize()
            .semantics { paneTitle = pageTitle }
            .padding(
                start = MagicHandyTheme.dimens.m,
                end = MagicHandyTheme.dimens.m,
                bottom = MagicHandyTheme.dimens.cozy,
            )
    ) {
        SetupHeader(step = 1, onBack = null, backTo = null)
        Column(
            Modifier.weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = MagicHandyTheme.dimens.m),
            verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.roomy),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.size(MagicHandyTheme.dimens.brandMark),
                )
                Text(
                    title,
                    style = MagicHandyTheme.type.gateTitle,
                    modifier =
                        Modifier.padding(start = MagicHandyTheme.dimens.m).semantics { heading() },
                )
            }
            Card(contentPadding = PaddingValues(MagicHandyTheme.dimens.m)) {
                Text(
                    stringResource(R.string.onboarding_adults_title),
                    style = MagicHandyTheme.type.bodyStrong,
                )
                Text(
                    stringResource(R.string.onboarding_adults_body),
                    style =
                        MagicHandyTheme.type.small.copy(
                            fontSize = MagicHandyTheme.type.label.fontSize
                        ),
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = MagicHandyTheme.dimens.snug),
                )
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = MagicHandyTheme.dimens.touch)
                        .padding(top = MagicHandyTheme.dimens.xs)
                        .toggleable(
                            value = isAdult,
                            enabled = !hasAcknowledged,
                            role = Role.Checkbox,
                        ) {
                            isAdult = it
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckBox(isAdult)
                    Text(
                        stringResource(R.string.onboarding_adults_check),
                        style = MagicHandyTheme.type.body,
                        modifier = Modifier.padding(start = MagicHandyTheme.dimens.m),
                    )
                }
            }
            Card(contentPadding = PaddingValues(MagicHandyTheme.dimens.m)) {
                Text(
                    stringResource(R.string.onboarding_stop_title),
                    style = MagicHandyTheme.type.bodyStrong,
                )
                Text(
                    stringResource(R.string.onboarding_stop_body),
                    style =
                        MagicHandyTheme.type.small.copy(
                            fontSize = MagicHandyTheme.type.label.fontSize
                        ),
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = MagicHandyTheme.dimens.snug),
                )
            }
        }
        AppButton(
            stringResource(R.string.setup_continue),
            onContinue,
            kind = ButtonKind.Primary,
            isEnabled = isAdult,
            modifier = Modifier.fillMaxWidth().padding(top = MagicHandyTheme.dimens.m),
        )
    }
}

package com.magichandy.mobile.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.magichandy.mobile.R
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.devices.CheckBox
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Before you start (board V-Onboarding): one page with the adults-only acknowledgement and a real
 * Stop rehearsal with nothing connected. "Start chatting" sits just above Stop, so the rehearsal's
 * receipt appears between them. Permissions are asked for later, where they're needed.
 */
@Composable
fun BeforeYouStartScreen(onAcknowledge: () -> Unit, modifier: Modifier = Modifier) {
    var isAdult by rememberSaveable { mutableStateOf(false) }
    val colors = MagicHandyTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .padding(
                start = MagicHandyTheme.dimens.l,
                end = MagicHandyTheme.dimens.l,
                top = MagicHandyTheme.dimens.xxl,
                bottom = MagicHandyTheme.dimens.m,
            )
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.roomy),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(R.drawable.ic_launcher_mark),
                    contentDescription = stringResource(R.string.app_name),
                    modifier =
                        Modifier.size(MagicHandyTheme.dimens.brandMark)
                            .clip(MagicHandyTheme.shapes.card)
                            .background(colors.surface),
                )
                Text(
                    stringResource(R.string.onboarding_title),
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
                        .toggleable(value = isAdult, role = Role.Checkbox) { isAdult = it },
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
            stringResource(R.string.onboarding_start),
            onAcknowledge,
            kind = ButtonKind.Primary,
            isEnabled = isAdult,
            modifier = Modifier.fillMaxWidth().padding(top = MagicHandyTheme.dimens.m),
        )
    }
}

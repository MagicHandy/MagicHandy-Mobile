package com.magichandy.mobile.ui.personas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.PersonaLimits
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.PageHeader
import com.magichandy.mobile.ui.components.StatusDot
import com.magichandy.mobile.ui.format.PersonaWords
import com.magichandy.mobile.ui.models.Page
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Personas (board V-Personas): MagicHandy first, then the person's own by name. Each opens its
 * editor; New persona opens an empty one.
 */
@Composable
fun PersonasScreen(
    state: AppState,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onEdit: (PersonaId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val own = settings.personas.count { !it.isBuiltIn }
    val isFull = own >= PersonaLimits.PERSONAS
    val settingsTitle = stringResource(R.string.settings_title)
    Page(modifier) {
        PageHeader(
            title = stringResource(R.string.personas_title),
            backDescription = stringResource(R.string.back_to_page, settingsTitle),
            onBack = onBack,
            breadcrumb = settingsTitle,
        )
        Text(
            stringResource(R.string.personas_lead),
            style = MagicHandyTheme.type.small,
            color = MagicHandyTheme.colors.muted,
        )
        AppButton(
            stringResource(R.string.personas_new),
            onNew,
            isEnabled = !isFull,
            modifier = Modifier.fillMaxWidth(),
            leading = {
                Icon(
                    AppIcons.Plus,
                    contentDescription = null,
                    tint = MagicHandyTheme.colors.accent,
                    modifier = Modifier.size(MagicHandyTheme.dimens.smallIcon),
                )
            },
        )
        if (isFull) Hint(stringResource(R.string.personas_limit))
        Card {
            settings.personas.forEachIndexed { index, persona ->
                PersonaRow(
                    persona = persona,
                    isInUse = persona.id == settings.persona.id,
                    isFirst = index == 0,
                    onClick = { onEdit(persona.id) },
                )
            }
        }
        if (own == 0) Hint(stringResource(R.string.personas_empty))
    }
}

@Composable
private fun PersonaRow(
    persona: PersonaRef,
    isInUse: Boolean,
    isFirst: Boolean,
    onClick: () -> Unit,
) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val personality = persona.personality
    val line =
        if (persona.isBuiltIn) {
            stringResource(
                R.string.persona_line_built_in,
                PersonaWords.level(personality.explicitness),
                PersonaWords.style(personality.style),
            )
        } else {
            PersonaWords.line(personality)
        }
    val label =
        stringResource(
            if (isInUse) R.string.persona_edit_in_use else R.string.persona_edit,
            persona.name,
        )
    CardRow(
        isFirst = isFirst,
        minHeight = dimens.deviceRow,
        verticalPadding = dimens.cozy,
        modifier =
            Modifier.clickable(role = Role.Button, onClick = onClick).semantics {
                contentDescription = label
                stateDescription = line
            },
    ) {
        PersonaTile(persona, persona.name)
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.s),
            ) {
                Text(
                    persona.name,
                    style = MagicHandyTheme.type.bodyStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isInUse) {
                    StatusDot(DotTone.Idle)
                    Text(
                        stringResource(R.string.persona_in_use),
                        style = MagicHandyTheme.type.caption,
                        color = colors.textSecondary,
                    )
                }
            }
            Text(line, style = MagicHandyTheme.type.small, color = colors.muted)
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = colors.faint,
            modifier = Modifier.size(dimens.smallIcon),
        )
    }
}

package com.magichandy.mobile.ui.personas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.PersonaLimits
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.ReactionStyle
import com.magichandy.mobile.core.api.ReplyLength
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.BrandMark
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.LabelValue
import com.magichandy.mobile.ui.components.RadioMark
import com.magichandy.mobile.ui.components.Segment
import com.magichandy.mobile.ui.components.Segmented
import com.magichandy.mobile.ui.components.SheetHeader
import com.magichandy.mobile.ui.components.SheetOverlay
import com.magichandy.mobile.ui.components.segmentedWidthFor
import com.magichandy.mobile.ui.format.PersonaWords
import com.magichandy.mobile.ui.settings.ChoiceList
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * One choice as its own card: [label] with the current [value], and what that value means under it
 * (boards V-PersonaDefault and V-SetupCompanion). Tapping it opens the choices.
 *
 * @param detailLines how many lines [detail] may take before it's cut short.
 */
@Composable
internal fun PickerCard(
    label: String,
    value: String?,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detailLines: Int = Int.MAX_VALUE,
) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val shape = MagicHandyTheme.shapes.card
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(dimens.hairline, colors.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = dimens.listRow)
            .padding(horizontal = dimens.m, vertical = dimens.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.cozy),
    ) {
        Column(Modifier.weight(1f)) {
            LabelValue(
                label = { Text(label, style = MagicHandyTheme.type.label) },
                value = {
                    if (value != null) {
                        Text(
                            value,
                            style = MagicHandyTheme.type.small,
                            color = colors.textSecondary,
                            textAlign = TextAlign.End,
                        )
                    }
                },
            )
            Text(
                detail,
                style = MagicHandyTheme.type.caption,
                color = colors.muted,
                maxLines = detailLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = dimens.xxs),
            )
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = colors.faint,
            modifier = Modifier.size(dimens.smallIcon),
        )
    }
}

/** The four levels as radio rows, each with its definition (board V-SetupCompanion). */
@Composable
internal fun LevelChoices(
    selected: Explicitness,
    onSelect: (Explicitness) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.selectableGroup()) {
        Explicitness.entries.forEachIndexed { index, level ->
            val isOn = level == selected
            CardRow(
                isFirst = index == 0,
                minHeight = MagicHandyTheme.dimens.listRow,
                verticalPadding = MagicHandyTheme.dimens.s,
                modifier = Modifier.selectable(isOn, role = Role.RadioButton) { onSelect(level) },
            ) {
                RadioMark(isOn)
                Column(Modifier.weight(1f)) {
                    Text(PersonaWords.level(level), style = MagicHandyTheme.type.label)
                    Text(
                        PersonaWords.levelDefinition(level),
                        style = MagicHandyTheme.type.small,
                        color = MagicHandyTheme.colors.muted,
                    )
                }
            }
        }
    }
}

/**
 * The personality description: a multiline field with a character counter. Over the limit, the
 * counter turns red and the line under the field says how much to remove; the core would refuse it.
 *
 * @param helper the line under the field while the description fits.
 * @param fieldModifier applies to the text field itself, such as a focus requester.
 */
@Composable
internal fun DescriptionField(
    draft: PersonaDraft,
    onChange: (String) -> Unit,
    helper: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val shape = MagicHandyTheme.shapes.control
    val label = stringResource(R.string.persona_description)
    val isOver = draft.excess > 0
    val value = draft.personality.description
    Column(modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = MagicHandyTheme.type.body.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier =
                fieldModifier
                    .fillMaxWidth()
                    .heightIn(min = dimens.descriptionField)
                    .clip(shape)
                    .background(colors.well)
                    .border(dimens.hairline, if (isOver) colors.danger else colors.fieldLine, shape)
                    .semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = dimens.m, vertical = dimens.cozy)) {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(R.string.persona_description_hint),
                            style = MagicHandyTheme.type.body,
                            color = colors.faint,
                        )
                    }
                    inner()
                }
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(top = dimens.snug),
            horizontalArrangement = Arrangement.spacedBy(dimens.m),
        ) {
            Text(
                if (isOver) {
                    pluralStringResource(R.plurals.persona_over_limit, draft.excess, draft.excess)
                } else {
                    helper
                },
                style = MagicHandyTheme.type.caption,
                color = if (isOver) colors.dangerText else colors.muted,
                modifier =
                    Modifier.weight(1f).semantics {
                        if (isOver) liveRegion = LiveRegionMode.Polite
                    },
            )
            val counted =
                stringResource(
                    R.string.persona_characters,
                    draft.descriptionLength,
                    PersonaLimits.DESCRIPTION,
                )
            Text(
                stringResource(
                    R.string.character_count,
                    draft.descriptionLength,
                    PersonaLimits.DESCRIPTION,
                ),
                style = MagicHandyTheme.type.mono,
                color = if (isOver) colors.dangerText else colors.muted,
                modifier = Modifier.semantics { contentDescription = counted },
            )
        }
    }
}

/** The description in its own card, as the editors show it (board V-PersonaDefault). */
@Composable
internal fun DescriptionCard(
    draft: PersonaDraft,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier,
        contentPadding =
            PaddingValues(
                horizontal = MagicHandyTheme.dimens.m,
                vertical = MagicHandyTheme.dimens.cozy,
            ),
    ) {
        Text(
            stringResource(R.string.persona_description),
            style = MagicHandyTheme.type.label,
            modifier = Modifier.padding(bottom = MagicHandyTheme.dimens.snug),
        )
        DescriptionField(
            draft,
            onChange,
            helper =
                stringResource(
                    if (draft.personality.explicitness == Explicitness.Utility) {
                        R.string.persona_description_saved
                    } else {
                        R.string.persona_description_active
                    }
                ),
        )
    }
}

/** "Reply length" beside Short and Detailed. With large text the choice wraps under its label. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReplyLengthCard(
    selected: ReplyLength,
    onSelect: (ReplyLength) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier,
        contentPadding =
            PaddingValues(
                horizontal = MagicHandyTheme.dimens.m,
                vertical = MagicHandyTheme.dimens.s,
            ),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.cozy),
            verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.snug),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.reply_length),
                style = MagicHandyTheme.type.label,
                modifier = Modifier.weight(1f),
            )
            val segments = ReplyLength.entries.map { Segment(it, PersonaWords.replyLength(it)) }
            Segmented(
                segments = segments,
                selected = selected,
                onSelect = onSelect,
                modifier =
                    Modifier.weight(1f).widthIn(min = segmentedWidthFor(segments.map { it.label })),
            )
        }
    }
}

/**
 * A persona's tile: the brand mark for MagicHandy and the first letter of the name for the others
 * (board V-Personas). It's decorative; the name beside it says who it is.
 */
@Composable
internal fun PersonaTile(persona: PersonaRef?, name: String, modifier: Modifier = Modifier) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    if (persona?.isBuiltIn == true) {
        BrandMark(contentDescription = null, modifier = modifier.size(dimens.pageTile))
    } else {
        Box(
            modifier
                .size(dimens.pageTile)
                .clip(MagicHandyTheme.shapes.card)
                .background(colors.raised),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initial(name),
                style = MagicHandyTheme.type.title,
                color = colors.textSecondary,
            )
        }
    }
}

/** The first letter of [name] in upper case, or nothing for a name not yet typed. */
private fun initial(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return ""
    val end = trimmed.offsetByCodePoints(0, 1)
    return trimmed.substring(0, end).uppercase()
}

/** Which of a persona's choices a picker sheet is showing. */
internal enum class PersonaPicker {
    Level,
    Style,
    Description,
}

/**
 * The sheet for one of a persona's choices, over the page (board V-PersonaStyle). Choosing a level
 * or style closes it; the description has Done.
 */
@Composable
internal fun BoxScope.PersonaPickerSheet(
    picker: PersonaPicker,
    draft: PersonaDraft,
    onDraft: (PersonaDraft) -> Unit,
    onClose: () -> Unit,
) {
    val title =
        stringResource(
            when (picker) {
                PersonaPicker.Level -> R.string.how_explicit
                PersonaPicker.Style -> R.string.reaction_style
                PersonaPicker.Description -> R.string.persona_description
            }
        )
    val personality = draft.personality
    SheetOverlay(title = title, onDismiss = onClose) {
        SheetHeader(title = title, onClose = onClose)
        when (picker) {
            PersonaPicker.Level -> {
                ChoiceList(
                    options = Explicitness.entries,
                    selected = personality.explicitness,
                    label = { PersonaWords.level(it) },
                    description = { PersonaWords.levelDefinition(it) },
                    onSelect = {
                        onDraft(draft.copy(personality = personality.copy(explicitness = it)))
                        onClose()
                    },
                )
                Hint(
                    stringResource(R.string.persona_wording_only),
                    Modifier.padding(top = MagicHandyTheme.dimens.s),
                )
            }
            PersonaPicker.Style -> {
                ChoiceList(
                    options = ReactionStyle.entries,
                    selected = personality.style,
                    label = { PersonaWords.style(it) },
                    description = { PersonaWords.styleDefinition(it) },
                    onSelect = {
                        onDraft(draft.copy(personality = personality.copy(style = it)))
                        onClose()
                    },
                )
                Hint(
                    stringResource(R.string.reaction_style_note),
                    Modifier.padding(top = MagicHandyTheme.dimens.s),
                )
            }
            PersonaPicker.Description -> {
                // The sheet is only for writing, so the keyboard opens with it.
                val field = remember { FocusRequester() }
                LaunchedEffect(Unit) { field.requestFocus() }
                DescriptionField(
                    draft,
                    { onDraft(draft.copy(personality = personality.copy(description = it))) },
                    helper = stringResource(R.string.persona_description_used),
                    fieldModifier = Modifier.focusRequester(field),
                )
                AppButton(
                    stringResource(R.string.action_done),
                    onClose,
                    kind = ButtonKind.Primary,
                    modifier = Modifier.fillMaxWidth().padding(top = MagicHandyTheme.dimens.m),
                )
            }
        }
    }
}

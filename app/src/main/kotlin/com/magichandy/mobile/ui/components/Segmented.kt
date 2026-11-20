package com.magichandy.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.magichandy.mobile.R
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** One choice of a [Segmented] control. */
data class Segment<T>(val value: T, val label: String, val note: String? = null)

/**
 * A segmented choice: the selected option is raised; an optional note sits under its label. Each
 * option's touch target spans the full height of the well, at least 48 dp.
 */
@Composable
fun <T> Segmented(
    segments: List<Segment<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = MagicHandyTheme.dimens.segment,
) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.control
    val inset = MagicHandyTheme.dimens.tight
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.well)
            .border(MagicHandyTheme.dimens.hairline, colors.line, shape)
            .padding(horizontal = inset)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(inset),
    ) {
        for (segment in segments) {
            val isOn = segment.value == selected
            Column(
                Modifier.weight(1f)
                    .heightIn(min = maxOf(minHeight + inset * 2, MagicHandyTheme.dimens.touch))
                    .selectable(selected = isOn, role = Role.RadioButton) {
                        onSelect(segment.value)
                    }
                    .padding(vertical = inset)
                    .heightIn(min = minHeight)
                    .clip(MagicHandyTheme.shapes.small)
                    .background(if (isOn) colors.raised else Color.Transparent)
                    .padding(
                        horizontal = MagicHandyTheme.dimens.xs,
                        vertical = MagicHandyTheme.dimens.xs,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    segment.label,
                    style = MagicHandyTheme.type.small,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isOn) colors.text else colors.muted,
                    textAlign = TextAlign.Center,
                )
                if (segment.note != null) {
                    Text(
                        segment.note,
                        style =
                            MagicHandyTheme.type.caption.copy(
                                fontSize = MagicHandyTheme.type.overline.fontSize
                            ),
                        color = colors.accent,
                    )
                }
            }
        }
    }
}

/** A compact picker: the current value in a field with a caret, opening a menu of [options]. */
@Composable
fun <T> ValueMenu(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.control
    var isOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.minimumInteractiveComponentSize()
                .heightIn(min = MagicHandyTheme.dimens.compactControl)
                .clip(shape)
                .background(colors.well)
                .border(MagicHandyTheme.dimens.hairline, colors.fieldLine, shape)
                .clickable(role = Role.DropdownList) { isOpen = true }
                .padding(horizontal = MagicHandyTheme.dimens.cozy),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label(selected), style = MagicHandyTheme.type.small)
            Icon(
                AppIcons.ChevronDown,
                contentDescription = null,
                tint = colors.muted,
                modifier =
                    Modifier.padding(start = MagicHandyTheme.dimens.snug)
                        .size(MagicHandyTheme.dimens.menuCaret),
            )
        }
        DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(label(option), style = MagicHandyTheme.type.small) },
                    onClick = {
                        isOpen = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/**
 * A minus, value, plus stepper row, one-handed (plan §16).
 *
 * @param outOf the most [value] can reach, shown after it in a quieter style, such as a Coyote
 *   channel's limit.
 * @param canDown false when stepping down can't do anything, such as at zero.
 * @param canUp false when stepping up can't do anything, such as at a limit.
 */
@Composable
fun StepperRow(
    label: String,
    value: String,
    downDescription: String,
    upDescription: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
    isFirst: Boolean,
    modifier: Modifier = Modifier,
    outOf: String? = null,
    canDown: Boolean = true,
    canUp: Boolean = true,
) {
    CardRow(
        isFirst = isFirst,
        modifier = modifier,
        minHeight = MagicHandyTheme.dimens.stepperRow,
        verticalPadding = MagicHandyTheme.dimens.xs,
    ) {
        Text(label, style = MagicHandyTheme.type.body, modifier = Modifier.weight(1f))
        StepKey(AppIcons.Minus, downDescription, canDown, onDown)
        val readout =
            if (outOf == null) {
                AnnotatedString(value)
            } else {
                buildAnnotatedString {
                    append(value)
                    withStyle(
                        SpanStyle(
                            color = MagicHandyTheme.colors.muted,
                            fontSize = MagicHandyTheme.type.small.fontSize,
                            fontWeight = FontWeight.Normal,
                        )
                    ) {
                        append(stringResource(R.string.out_of, outOf))
                    }
                }
            }
        Text(
            readout,
            style = MagicHandyTheme.type.monoValue,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = MagicHandyTheme.dimens.readoutWidth),
        )
        StepKey(AppIcons.Plus, upDescription, canUp, onUp)
    }
}

@Composable
private fun StepKey(
    icon: ImageVector,
    description: String,
    isEnabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.control
    Box(
        Modifier.size(MagicHandyTheme.dimens.touch)
            .alpha(if (isEnabled) 1f else DISABLED_ALPHA)
            .clip(shape)
            .background(colors.raised)
            .border(MagicHandyTheme.dimens.hairline, colors.strongLine, shape)
            .clickable(enabled = isEnabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(MagicHandyTheme.dimens.actionIcon),
        )
    }
}

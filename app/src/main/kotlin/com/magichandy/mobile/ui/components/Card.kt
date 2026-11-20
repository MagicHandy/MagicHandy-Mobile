package com.magichandy.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** An uppercase section caption. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MagicHandyTheme.type.overline,
        color = MagicHandyTheme.colors.muted,
        modifier = modifier.semantics { heading() },
    )
}

/** A bordered card that groups rows. A [caption] sits inside it, above the rows. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    caption: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = MagicHandyTheme.dimens.m),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.card
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(MagicHandyTheme.dimens.hairline, colors.line, shape)
            .padding(contentPadding)
    ) {
        if (caption != null)
            Caption(
                caption,
                Modifier.padding(
                    top = MagicHandyTheme.dimens.cozy,
                    bottom = MagicHandyTheme.dimens.xxs,
                ),
            )
        content()
    }
}

/** A row inside a [Card], with a hairline above every row but the first. */
@Composable
fun CardRow(
    isFirst: Boolean,
    modifier: Modifier = Modifier,
    minHeight: Dp = MagicHandyTheme.dimens.control,
    verticalPadding: Dp = MagicHandyTheme.dimens.snug,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (isFirst) Modifier else Modifier.topHairline(MagicHandyTheme.colors.line))
            .heightIn(min = minHeight)
            .padding(vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.cozy),
        content = content,
    )
}

/**
 * A label with an optional description and a value, opening a detail when tapped. A [valueTone]
 * puts a status dot before the value.
 */
@Composable
fun SettingRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
    isFirst: Boolean,
    modifier: Modifier = Modifier,
    description: String? = null,
    valueColor: Color? = null,
    labelColor: Color? = null,
    valueTone: DotTone? = null,
) {
    CardRow(
        isFirst = isFirst,
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
    ) {
        LabelValue(
            label = { LabelBlock(label, description, Modifier, labelColor) },
            value = {
                if (value != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (valueTone != null) {
                            StatusDot(valueTone, Modifier.padding(end = MagicHandyTheme.dimens.s))
                        }
                        Text(
                            value,
                            style = MagicHandyTheme.type.small,
                            color = valueColor ?: MagicHandyTheme.colors.textSecondary,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = MagicHandyTheme.colors.faint,
            modifier = Modifier.size(MagicHandyTheme.dimens.smallIcon),
        )
    }
}

/** A label with an optional description and a switch. */
@Composable
fun ToggleRow(
    label: String,
    isOn: Boolean,
    onChange: (Boolean) -> Unit,
    isFirst: Boolean,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    CardRow(
        isFirst = isFirst,
        modifier = modifier.toggleable(value = isOn, role = Role.Switch, onValueChange = onChange),
    ) {
        LabelBlock(label, description, Modifier.weight(1f))
        SwitchGraphic(isOn)
    }
}

/** A label with an optional description and a value that can't be changed here. */
@Composable
fun FixedRow(
    label: String,
    value: AnnotatedString,
    isFirst: Boolean,
    modifier: Modifier = Modifier,
    description: String? = null,
    valueColor: Color? = null,
) {
    CardRow(isFirst = isFirst, modifier = modifier.semantics(mergeDescendants = true) {}) {
        LabelValue(
            label = { LabelBlock(label, description, Modifier) },
            value = {
                Text(
                    value,
                    style = MagicHandyTheme.type.small,
                    color = valueColor ?: MagicHandyTheme.colors.textSecondary,
                    textAlign = TextAlign.End,
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A label and its value on one line. The value keeps its natural width while both fit. When they
 * don't, it gives way down to half the row, so large text wraps between words instead of breaking
 * the label mid-word (system design §8).
 */
@Composable
fun LabelValue(
    label: @Composable () -> Unit,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = MagicHandyTheme.dimens.m
    Layout(contents = listOf(label, value), modifier = modifier) { (labels, values), constraints ->
        val labelPart = labels.single()
        val valuePart = values.singleOrNull()
        val available = (constraints.maxWidth - gap.roundToPx()).coerceAtLeast(0)
        val valueWidth =
            if (valuePart == null || !constraints.hasBoundedWidth) 0
            else {
                val labelNatural = labelPart.maxIntrinsicWidth(constraints.maxHeight)
                val valueNatural = valuePart.maxIntrinsicWidth(constraints.maxHeight)
                minOf(valueNatural, maxOf(available - labelNatural, available / 2))
            }
        val valuePlaced = valuePart?.measure(Constraints(maxWidth = valueWidth))
        val labelWidth = (available - (valuePlaced?.width ?: 0)).coerceAtLeast(0)
        val labelPlaced =
            labelPart.measure(Constraints(minWidth = labelWidth, maxWidth = labelWidth))
        val height = maxOf(labelPlaced.height, valuePlaced?.height ?: 0, constraints.minHeight)
        layout(constraints.maxWidth, height) {
            labelPlaced.place(0, (height - labelPlaced.height) / 2)
            valuePlaced?.place(
                constraints.maxWidth - valuePlaced.width,
                (height - valuePlaced.height) / 2,
            )
        }
    }
}

/** [FixedRow] with a plain value. */
@Composable
fun FixedRow(
    label: String,
    value: String,
    isFirst: Boolean,
    modifier: Modifier = Modifier,
    description: String? = null,
    valueColor: Color? = null,
) = FixedRow(label, AnnotatedString(value), isFirst, modifier, description, valueColor)

/** A label with an optional description, and [trailing] content of the caller's choosing. */
@Composable
fun LabelRow(
    label: String,
    isFirst: Boolean,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailing: @Composable () -> Unit,
) {
    CardRow(isFirst = isFirst, modifier = modifier) {
        LabelBlock(label, description, Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun LabelBlock(
    label: String,
    description: String?,
    modifier: Modifier,
    labelColor: Color? = null,
) {
    Column(modifier) {
        Text(
            label,
            style = MagicHandyTheme.type.body,
            color = labelColor ?: MagicHandyTheme.colors.text,
        )
        if (description != null) {
            Text(
                description,
                style = MagicHandyTheme.type.caption,
                color = MagicHandyTheme.colors.muted,
                modifier = Modifier.padding(top = MagicHandyTheme.dimens.xxs),
            )
        }
    }
}

/** The switch drawing. The row it sits in carries the toggle semantics. */
@Composable
fun SwitchGraphic(isOn: Boolean) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    Box(
        Modifier.size(dimens.switchWidth, dimens.switchHeight)
            .clip(CircleShape)
            .background(if (isOn) colors.accent else colors.strongLine),
        contentAlignment = Alignment.CenterStart,
    ) {
        val travel = dimens.switchWidth - dimens.switchThumb - dimens.switchInset * 2
        Box(
            Modifier.offset(x = if (isOn) dimens.switchInset + travel else dimens.switchInset)
                .size(dimens.switchThumb)
                .clip(CircleShape)
                .background(if (isOn) colors.onAccent else colors.muted)
        )
    }
}

/** The radio drawing. The row it sits in carries the radio-button semantics. */
@Composable
fun RadioMark(isSelected: Boolean) {
    val colors = MagicHandyTheme.colors
    Box(
        Modifier.size(MagicHandyTheme.dimens.radioMark)
            .clip(CircleShape)
            .border(
                MagicHandyTheme.dimens.markStroke,
                if (isSelected) colors.accent else colors.muted,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected)
            Box(
                Modifier.size(MagicHandyTheme.dimens.radioDot)
                    .clip(CircleShape)
                    .background(colors.accent)
            )
    }
}

package com.magichandy.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** The visual weight of a button. */
enum class ButtonKind {
    /** Accent fill: the main next step. */
    Primary,

    /** Outline: secondary actions. */
    Outline,

    /** Green fill: explicitly starting motion, such as Test motor (plan §14.1). */
    Start,

    /** Red outline: removing something. */
    Destructive,

    /** Accent tint: a toggle that is on. */
    Selected,
}

/**
 * A button. It's [height] tall, 48 dp by default; a compact one keeps a 48 dp touch target around
 * it.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Outline,
    isEnabled: Boolean = true,
    height: Dp = MagicHandyTheme.dimens.control,
    leading: (@Composable () -> Unit)? = null,
    hasChevron: Boolean = false,
) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.control
    val (fill, border, content) =
        when (kind) {
            ButtonKind.Primary -> Triple(colors.accent, null, colors.onAccent)
            ButtonKind.Outline -> Triple(Color.Transparent, colors.strongLine, colors.text)
            ButtonKind.Start -> Triple(colors.start, null, colors.onStart)
            ButtonKind.Destructive -> Triple(Color.Transparent, colors.danger, colors.dangerText)
            ButtonKind.Selected -> Triple(colors.accentContainer, null, colors.text)
        }
    val alpha = if (isEnabled) 1f else DISABLED_ALPHA
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = height)
            .clip(shape)
            .background(fill.copy(alpha = fill.alpha * alpha))
            .then(
                if (border != null) {
                    Modifier.border(
                        MagicHandyTheme.dimens.hairline,
                        border.copy(alpha = alpha),
                        shape,
                    )
                } else {
                    Modifier
                }
            )
            .clickable(enabled = isEnabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = MagicHandyTheme.dimens.roomy),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(MagicHandyTheme.dimens.snug))
        }
        Text(
            text,
            style = MagicHandyTheme.type.label,
            color = content.copy(alpha = alpha),
            textAlign = TextAlign.Center,
        )
        if (hasChevron) {
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = content.copy(alpha = alpha),
                modifier =
                    Modifier.padding(start = MagicHandyTheme.dimens.xxs)
                        .size(MagicHandyTheme.dimens.inlineIcon),
            )
        }
    }
}

/**
 * Buttons sharing one row in proportion to [weights], one per button in order. When a label
 * wouldn't fit its share, as with large text or a long translation, they stack at full width
 * instead of breaking a word.
 */
@Composable
fun ButtonRow(
    weights: List<Float>,
    modifier: Modifier = Modifier,
    gap: Dp = MagicHandyTheme.dimens.s,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { buttons, constraints ->
        check(buttons.size == weights.size) { "One weight per button" }
        val gapPx = gap.roundToPx()
        val width = constraints.maxWidth
        val free = (width - gapPx * (buttons.size - 1)).coerceAtLeast(0)
        val shares = weights.map { (free * it / weights.sum()).toInt() }
        val fitsInRow =
            buttons.indices.all {
                buttons[it].maxIntrinsicWidth(Constraints.Infinity) <= shares[it]
            }
        if (fitsInRow) {
            val placed = buttons.mapIndexed { i, button ->
                button.measure(Constraints.fixedWidth(shares[i]))
            }
            val height = placed.maxOf { it.height }
            layout(width, height) {
                var x = 0
                placed.forEach {
                    it.place(x, (height - it.height) / 2)
                    x += it.width + gapPx
                }
            }
        } else {
            val placed = buttons.map { it.measure(Constraints.fixedWidth(width)) }
            val height = placed.sumOf { it.height } + gapPx * (placed.size - 1)
            layout(width, height) {
                var y = 0
                placed.forEach {
                    it.place(0, y)
                    y += it.height + gapPx
                }
            }
        }
    }
}

/** A square icon button with a required description. */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val dimens = MagicHandyTheme.dimens
    Box(
        modifier
            .size(dimens.touch)
            .clip(MagicHandyTheme.shapes.control)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint ?: MagicHandyTheme.colors.text,
            modifier = Modifier.size(dimens.icon),
        )
    }
}

/** A page heading with Back, and an optional breadcrumb beside Back with the title below it. */
@Composable
fun PageHeader(
    title: String,
    backDescription: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    breadcrumb: String? = null,
    isLarge: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(
                AppIcons.Back,
                backDescription,
                onBack,
                Modifier.offset(x = -MagicHandyTheme.dimens.backOffset),
            )
            when {
                breadcrumb != null ->
                    Text(
                        breadcrumb,
                        style = MagicHandyTheme.type.small,
                        color = MagicHandyTheme.colors.muted,
                    )
                !isLarge ->
                    Text(
                        title,
                        style =
                            MagicHandyTheme.type.title.copy(
                                fontSize = MagicHandyTheme.type.stop.fontSize
                            ),
                        modifier = Modifier.semantics { heading() },
                    )
            }
        }
        if (breadcrumb != null || isLarge) {
            Text(
                title,
                style = MagicHandyTheme.type.pageTitle,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

/** The opacity of a control that can't be used right now. */
internal const val DISABLED_ALPHA = 0.45f

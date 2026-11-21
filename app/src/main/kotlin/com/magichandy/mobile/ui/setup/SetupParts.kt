package com.magichandy.mobile.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.magichandy.mobile.R
import com.magichandy.mobile.ui.components.AnchoredColumn
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.IconAction
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** How many steps setup has: Before you start, Choose your AI, Your companion and You're set. */
const val SETUP_STEPS: Int = 4

/**
 * A setup step's page (boards V-SetupModel to V-SetupReady): Back with "Setup" and the step, a bar
 * with one segment per step, then the [title] and [lead]. TalkBack hears the page once as "Choose
 * your AI, step 2 of 4". Without [onBack], as on the first step, there's no Back.
 *
 * @param backTo the title of the page Back returns to.
 */
@Composable
internal fun SetupPage(
    step: Int,
    title: String,
    lead: String?,
    onBack: (() -> Unit)?,
    backTo: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = MagicHandyTheme.dimens
    val pageTitle = stringResource(R.string.setup_page, title, step, SETUP_STEPS)
    AnchoredColumn(
        modifier.fillMaxSize().semantics { paneTitle = pageTitle },
        contentPadding = PaddingValues(start = dimens.m, end = dimens.m, bottom = dimens.cozy),
        verticalArrangement = Arrangement.spacedBy(dimens.cozy),
    ) {
        SetupHeader(step, onBack, backTo)
        Text(
            title,
            style = MagicHandyTheme.type.pageTitle,
            modifier = Modifier.semantics { heading() },
        )
        if (lead != null) {
            Text(
                lead,
                style =
                    MagicHandyTheme.type.small.copy(fontSize = MagicHandyTheme.type.label.fontSize),
                color = MagicHandyTheme.colors.textSecondary,
            )
        }
        content()
    }
}

/** Back with "Setup" and the step, then the progress bar; [SetupPage] puts it at the top. */
@Composable
internal fun SetupHeader(step: Int, onBack: (() -> Unit)?, backTo: String?) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = dimens.touch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null && backTo != null) {
                IconAction(
                    AppIcons.Back,
                    stringResource(R.string.back_to_page, backTo),
                    onBack,
                    Modifier.offset(x = -dimens.backOffset),
                )
            }
            Text(
                stringResource(R.string.setup_label),
                style = MagicHandyTheme.type.small,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.setup_step, step, SETUP_STEPS),
                style = MagicHandyTheme.type.small,
                color = colors.muted,
            )
        }
        // The step is already spoken with the page's title, so the bar is drawing only.
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(dimens.xs),
        ) {
            repeat(SETUP_STEPS) { index ->
                Box(
                    Modifier.weight(1f)
                        .height(dimens.progressBar)
                        .clip(MagicHandyTheme.shapes.small)
                        .background(if (index < step) colors.accent else colors.line)
                )
            }
        }
    }
}

/**
 * A provider on Choose your AI (board V-SetupModel): its tile, name, what it needs and where it
 * runs. Tapping it opens that provider's setup.
 */
@Composable
internal fun ProviderCard(
    icon: ImageVector,
    name: String,
    description: String,
    meta: String,
    onClick: () -> Unit,
) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val shape = MagicHandyTheme.shapes.card
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(dimens.hairline, colors.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(dimens.m),
        horizontalArrangement = Arrangement.spacedBy(dimens.m),
    ) {
        Box(
            Modifier.size(dimens.pageTile)
                .clip(MagicHandyTheme.shapes.control)
                .background(colors.raised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(dimens.icon))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dimens.xxs)) {
            Text(name, style = MagicHandyTheme.type.bodyStrong)
            Text(description, style = MagicHandyTheme.type.small, color = colors.textSecondary)
            Text(meta, style = MagicHandyTheme.type.caption, color = colors.muted)
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = colors.faint,
            modifier = Modifier.align(Alignment.CenterVertically).size(dimens.smallIcon),
        )
    }
}

/**
 * One line of You're set (board V-SetupReady): a done mark or an empty ring, the [label] and its
 * [value], and either a chevron that opens it or a text [action].
 */
@Composable
internal fun ReadyRow(
    label: String,
    value: String,
    isDone: Boolean,
    isFirst: Boolean,
    onClick: (() -> Unit)?,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    CardRow(
        isFirst = isFirst,
        minHeight = dimens.deviceRow,
        verticalPadding = dimens.s,
        modifier =
            if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
            else Modifier.semantics(mergeDescendants = true) {},
    ) {
        if (isDone) {
            Box(
                Modifier.size(dimens.readyMark)
                    .clip(CircleShape)
                    .background(colors.ok.copy(alpha = DONE_TINT)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Check,
                    contentDescription = null,
                    tint = colors.ok,
                    modifier = Modifier.size(dimens.inlineIcon),
                )
            }
        } else {
            Box(
                Modifier.size(dimens.readyMark).border(dimens.markStroke, colors.faint, CircleShape)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MagicHandyTheme.type.label)
            Text(value, style = MagicHandyTheme.type.small, color = colors.muted)
        }
        when {
            action != null ->
                Box(
                    Modifier.heightIn(min = dimens.touch)
                        .clip(MagicHandyTheme.shapes.control)
                        .clickable(role = Role.Button, onClick = onAction)
                        .padding(horizontal = dimens.xs),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        action,
                        style = MagicHandyTheme.type.label,
                        color = colors.accent,
                        textAlign = TextAlign.End,
                    )
                }
            onClick != null ->
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.faint,
                    modifier = Modifier.size(dimens.smallIcon),
                )
            else -> Spacer(Modifier)
        }
    }
}

/** How strongly the done mark's circle is tinted with the OK colour. */
private const val DONE_TINT = 0.15f

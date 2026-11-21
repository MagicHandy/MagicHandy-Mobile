package com.magichandy.mobile.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * A confirmation drawn inside the content area, not a system dialog, so Stop stays one tap away
 * while it's open. Tapping outside it or Back is the same as [dismiss]. Focus starts on [dismiss],
 * the safe choice.
 *
 * @param extra shown under [body], such as why the core refused the last attempt.
 */
@Composable
fun BoxScope.InlineDialog(
    title: String,
    body: String,
    dismiss: String,
    onDismiss: () -> Unit,
    confirm: String,
    onConfirm: () -> Unit,
    isDestructive: Boolean = false,
    isConfirmEnabled: Boolean = true,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val shape = MagicHandyTheme.shapes.card
    val safeChoice = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { safeChoice.requestFocus() }
    Box(
        Modifier.matchParentSize()
            .background(colors.scrim)
            .clickable(interactionSource = null, indication = null, onClick = onDismiss)
    )
    Column(
        Modifier.align(Alignment.Center)
            .padding(horizontal = dimens.l, vertical = dimens.xl)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(dimens.hairline, colors.strongLine, shape)
            .semantics { paneTitle = title }
            .verticalScroll(rememberScrollState())
            .padding(dimens.l),
        verticalArrangement = Arrangement.spacedBy(dimens.s),
    ) {
        Text(title, style = MagicHandyTheme.type.title, modifier = Modifier.semantics { heading() })
        Text(body, style = MagicHandyTheme.type.small, color = colors.textSecondary)
        extra()
        ButtonRow(weights = listOf(1f, 1f), modifier = Modifier.padding(top = dimens.s)) {
            AppButton(dismiss, onDismiss, Modifier.focusRequester(safeChoice))
            AppButton(
                confirm,
                onConfirm,
                kind = if (isDestructive) ButtonKind.Destructive else ButtonKind.Primary,
                isEnabled = isConfirmEnabled,
            )
        }
    }
}

/**
 * A short menu of actions under the top-end corner of the content area, such as a page's More
 * options. Like [InlineDialog], it stays inside the content area, so Stop is never covered.
 */
@Composable
fun BoxScope.InlineMenu(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val shape = MagicHandyTheme.shapes.card
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.matchParentSize()
            .clickable(interactionSource = null, indication = null, onClick = onDismiss)
    )
    Column(
        Modifier.align(Alignment.TopEnd)
            .padding(top = dimens.touch, end = dimens.m)
            .width(IntrinsicSize.Max)
            .widthIn(min = dimens.menuWidth)
            .clip(shape)
            .background(colors.raised)
            .border(dimens.hairline, colors.strongLine, shape)
            .semantics { paneTitle = title }
            .padding(vertical = dimens.xs),
        content = content,
    )
}

/** One action in an [InlineMenu]. */
@Composable
fun MenuAction(text: String, onClick: () -> Unit, color: Color? = null) {
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = MagicHandyTheme.dimens.touch)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = MagicHandyTheme.dimens.l),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MagicHandyTheme.type.body, color = color ?: MagicHandyTheme.colors.text)
    }
}

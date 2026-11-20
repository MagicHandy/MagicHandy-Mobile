package com.magichandy.mobile.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.magichandy.mobile.R
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * An in-window sheet over the content area (plan §14.2). It never covers the tabs or Stop. Tapping
 * the scrim calls [onDismiss]; system Back calls [onBack], which returns to the previous page of a
 * nested sheet.
 */
@Composable
fun BoxScope.SheetOverlay(
    title: String,
    onDismiss: () -> Unit,
    onBack: () -> Unit = onDismiss,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.sheet
    BackHandler(onBack = onBack)
    Box(
        Modifier.matchParentSize()
            .background(colors.scrim)
            .clickable(interactionSource = null, indication = null, onClick = onDismiss)
    )
    BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = maxHeight * 0.92f)
                .clip(shape)
                .background(colors.surface)
                .border(MagicHandyTheme.dimens.hairline, colors.strongLine, shape)
                .semantics { paneTitle = title }
                .verticalScroll(rememberScrollState())
                .padding(
                    start = MagicHandyTheme.dimens.l,
                    end = MagicHandyTheme.dimens.l,
                    top = MagicHandyTheme.dimens.xs,
                    bottom = MagicHandyTheme.dimens.m,
                ),
            content = content,
        )
    }
}

/** A sheet's heading: Back when nested, the title, and Close. */
@Composable
fun SheetHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backDescription: String? = null,
    subtitle: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MagicHandyTheme.dimens.sheetRow),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null && backDescription != null) {
                IconAction(
                    AppIcons.Back,
                    backDescription,
                    onBack,
                    Modifier.offset(x = -MagicHandyTheme.dimens.sheetBackOffset),
                )
            }
            Text(
                title,
                style = MagicHandyTheme.type.title.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            IconAction(
                AppIcons.Close,
                stringResource(R.string.action_close),
                onClose,
                Modifier.offset(x = MagicHandyTheme.dimens.sheetCloseOffset),
            )
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MagicHandyTheme.type.small,
                color = MagicHandyTheme.colors.muted,
                modifier = Modifier.padding(bottom = MagicHandyTheme.dimens.xs),
            )
        }
    }
}

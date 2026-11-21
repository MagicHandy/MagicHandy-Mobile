package com.magichandy.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** The tone of a [Banner]. */
enum class BannerTone {
    Danger,
    Warn,
}

/** A persistent notice: what happened, what it means, and one next step. */
@Composable
fun Banner(
    tone: BannerTone,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    hasIcon: Boolean = false,
) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.card
    val (fill, line) =
        when (tone) {
            BannerTone.Danger -> colors.dangerContainer to colors.dangerLine
            BannerTone.Warn -> colors.surface to colors.warnLine
        }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .border(MagicHandyTheme.dimens.hairline, line, shape)
            .padding(horizontal = MagicHandyTheme.dimens.m, vertical = MagicHandyTheme.dimens.cozy),
        verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.snug),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasIcon) {
                Icon(
                    AppIcons.Warning,
                    contentDescription = null,
                    tint = if (tone == BannerTone.Danger) colors.dangerText else colors.warn,
                    modifier =
                        Modifier.padding(end = MagicHandyTheme.dimens.s)
                            .size(MagicHandyTheme.dimens.inlineIcon),
                )
            }
            Text(title, style = MagicHandyTheme.type.bodyStrong)
        }
        if (body != null) {
            Text(
                body,
                style =
                    MagicHandyTheme.type.small.copy(
                        lineHeight = MagicHandyTheme.type.body.lineHeight
                    ),
                color = colors.textSecondary,
            )
        }
        if (actionLabel != null && onAction != null) {
            AppButton(
                actionLabel,
                onAction,
                kind = if (tone == BannerTone.Danger) ButtonKind.Primary else ButtonKind.Outline,
                height = MagicHandyTheme.dimens.compactControl,
                modifier = Modifier.padding(top = MagicHandyTheme.dimens.xxs),
            )
        }
    }
}

/** A short line of muted guidance. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(
        text,
        style =
            MagicHandyTheme.type.caption.copy(lineHeight = MagicHandyTheme.type.small.lineHeight),
        color = MagicHandyTheme.colors.muted,
        textAlign = textAlign,
        modifier = modifier,
    )
}

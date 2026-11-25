package com.magichandy.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.ui.theme.HairlineWidth
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** Whether the keyboard is open. While it is, the frame drops the tabs and keeps Stop above it. */
@OptIn(ExperimentalLayoutApi::class) @Composable fun isTyping(): Boolean = WindowInsets.isImeVisible

/**
 * The app frame (plan §14.2). From top to bottom: [content], then [aboveTabs] (voice input's
 * Listening chip), then [bottomBar] (the tabs), then Stop, which is the last row on every screen.
 * [overlay] draws sheets and the Stop receipt inside the content area, so they never cover Stop.
 * TalkBack gets a "Stop all motion" action from anywhere in the frame. [onStop] is told which
 * surface was used.
 */
@Composable
fun AppFrame(
    stopLabel: String,
    onStop: (StopSource) -> Unit,
    modifier: Modifier = Modifier,
    contentBackground: androidx.compose.ui.graphics.Color = MagicHandyTheme.colors.ground,
    bottomBar: (@Composable () -> Unit)? = null,
    aboveTabs: (@Composable () -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MagicHandyTheme.colors
    val stopAllMotion = stopResourceLabel()
    val haptics = LocalHapticFeedback.current
    val typing = isTyping()
    Column(
        modifier.fillMaxSize().background(colors.ground).semantics {
            customActions =
                listOf(
                    CustomAccessibilityAction(stopAllMotion) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onStop(StopSource.TalkBackAction)
                        true
                    }
                )
        }
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize()
                    .background(contentBackground)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                content()
            }
            overlay()
        }
        val showTabs = bottomBar != null && !typing
        aboveTabs?.invoke()
        if (bottomBar != null && showTabs) bottomBar()
        StopRow(
            label = stopLabel,
            onStop = onStop,
            hasTabsAbove = showTabs,
            hasChipAbove = aboveTabs != null && !showTabs,
        )
    }
}

@Composable private fun stopResourceLabel(): String = stringResource(R.string.stop_all_motion)

/**
 * The red Stop row. It gives haptic feedback on press, before any reply, and calls [onStop]
 * directly (system design §2). It grows with the font scale rather than clipping its label.
 */
@Composable
fun StopRow(
    label: String,
    onStop: (StopSource) -> Unit,
    hasTabsAbove: Boolean,
    modifier: Modifier = Modifier,
    hasChipAbove: Boolean = false,
) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .fillMaxWidth()
            .background(colors.chrome)
            .then(if (hasTabsAbove || hasChipAbove) Modifier else Modifier.topHairline(colors.line))
            .windowInsetsPadding(
                WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)
            )
            .padding(start = dimens.m, end = dimens.m, bottom = dimens.m)
            .then(if (hasTabsAbove) Modifier else Modifier.padding(top = dimens.s))
    ) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = dimens.stopRow)
                .clip(MagicHandyTheme.shapes.control)
                .background(colors.stop)
                .clickable(role = Role.Button) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onStop(StopSource.AppFooter)
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Stop,
                contentDescription = null,
                tint = colors.onStop,
                modifier = Modifier.size(dimens.smallIcon),
            )
            Spacer(Modifier.width(MagicHandyTheme.dimens.cozy))
            Text(label, style = MagicHandyTheme.type.stop, color = colors.onStop)
        }
    }
}

/** The two destinations of the bottom row. Settings is an action, not a destination. */
enum class MainTab {
    Chat,
    Devices,
}

/** Chat, Devices and the Settings action that opens the compact sheet (plan §14.2). */
@Composable
fun BottomTabs(
    selected: MainTab?,
    isSettingsOpen: Boolean,
    onSelect: (MainTab) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.chrome)
            .topHairline(colors.line)
            .padding(horizontal = dimens.m, vertical = MagicHandyTheme.dimens.snug)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(dimens.xs),
    ) {
        TabItem(
            label = stringResource(R.string.tab_chat),
            icon = AppIcons.Chat,
            isOn = selected == MainTab.Chat && !isSettingsOpen,
            modifier =
                Modifier.weight(1f)
                    .selectable(
                        selected = selected == MainTab.Chat,
                        role = Role.Tab,
                        onClick = { onSelect(MainTab.Chat) },
                    ),
        )
        TabItem(
            label = stringResource(R.string.tab_devices),
            icon = AppIcons.Devices,
            isOn = selected == MainTab.Devices && !isSettingsOpen,
            modifier =
                Modifier.weight(1f)
                    .selectable(
                        selected = selected == MainTab.Devices,
                        role = Role.Tab,
                        onClick = { onSelect(MainTab.Devices) },
                    ),
        )
        TabItem(
            label = stringResource(R.string.tab_settings),
            icon = AppIcons.Settings,
            isOn = isSettingsOpen,
            modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onSettings),
        )
    }
}

@Composable
private fun TabItem(label: String, icon: ImageVector, isOn: Boolean, modifier: Modifier) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    Column(
        modifier
            .heightIn(min = dimens.tab)
            .clip(MagicHandyTheme.shapes.control)
            .background(if (isOn) colors.accentContainer else colors.chrome),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isOn) colors.accent else colors.muted,
            modifier = Modifier.size(dimens.icon),
        )
        Spacer(Modifier.height(MagicHandyTheme.dimens.tight))
        Text(
            label,
            style = MagicHandyTheme.type.caption,
            fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Medium,
            color = if (isOn) colors.text else colors.muted,
        )
    }
}

/** Draws a one-pixel line along the top edge. */
fun Modifier.topHairline(color: androidx.compose.ui.graphics.Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = HairlineWidth.toPx())
}

/** Draws a one-pixel line along the bottom edge. */
fun Modifier.bottomHairline(color: androidx.compose.ui.graphics.Color): Modifier = drawBehind {
    val y = size.height - HairlineWidth.toPx() / 2
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = HairlineWidth.toPx())
}

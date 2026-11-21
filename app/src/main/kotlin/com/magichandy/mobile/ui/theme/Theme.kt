package com.magichandy.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.Palette

/** Inter, as on the design boards (plan §14.1). Bundled under the SIL Open Font License. */
private val Inter =
    FontFamily(
        Font(R.font.inter_400, FontWeight.Normal),
        Font(R.font.inter_500, FontWeight.Medium),
        Font(R.font.inter_600, FontWeight.SemiBold),
        Font(R.font.inter_700, FontWeight.Bold),
    )

/** JetBrains Mono, for numeric readouts such as paces, timers and latencies. */
private val Mono =
    FontFamily(
        Font(R.font.jetbrains_mono_400, FontWeight.Normal),
        Font(R.font.jetbrains_mono_600, FontWeight.SemiBold),
    )

/**
 * Text styles. Inter with tabular figures, so changing numbers don't shift nearby controls, and
 * JetBrains Mono for readouts (the `mono` styles).
 */
@Immutable
data class MagicHandyType(
    val figure: TextStyle,
    val pageTitle: TextStyle,
    /** Before you start's title, a step above a page title. */
    val gateTitle: TextStyle,
    /** A device page's heading: the device's name. */
    val deviceName: TextStyle,
    val title: TextStyle,
    /** The Stop receipt's heading. */
    val receiptHeading: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    /** What you type in the chat composer, a step above body text. */
    val composer: TextStyle,
    val label: TextStyle,
    val small: TextStyle,
    val caption: TextStyle,
    val overline: TextStyle,
    val stop: TextStyle,
    val mono: TextStyle,
    val monoValue: TextStyle,
    /** The room a reply's streaming caret takes in its text, in text units so it scales too. */
    val caretSpace: Placeholder,
)

private val Base = TextStyle(fontFamily = Inter, fontFeatureSettings = "tnum")

private val PageTitle =
    Base.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)

private val Title = Base.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)

private val Body = Base.copy(fontSize = 15.sp, lineHeight = 22.sp)

private val BodyStrong =
    Base.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)

private val Type =
    MagicHandyType(
        figure =
            Base.copy(
                fontSize = 44.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 46.sp,
                letterSpacing = (-0.02).em,
            ),
        pageTitle = PageTitle,
        gateTitle = PageTitle.copy(fontSize = PageTitle.fontSize * 1.18f),
        deviceName = PageTitle.copy(fontSize = PageTitle.fontSize * 1.1f),
        title = Title,
        receiptHeading = BodyStrong.copy(fontSize = Title.fontSize * 0.95f),
        body = Body,
        bodyStrong = BodyStrong,
        composer = Body.copy(fontSize = 16.sp),
        label = Base.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
        small = Base.copy(fontSize = 13.sp, lineHeight = 19.sp),
        caption = Base.copy(fontSize = 12.sp, lineHeight = 17.sp),
        overline =
            Base.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06.em,
                lineHeight = 16.sp,
            ),
        stop = Base.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp),
        mono = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp),
        monoValue =
            TextStyle(
                fontFamily = Mono,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 20.sp,
            ),
        caretSpace = Placeholder(4.sp, 16.sp, PlaceholderVerticalAlign.TextCenter),
    )

/** Corner radii: 10 for cards and sheets, 6 for controls, 4 for small details. */
@Immutable
data class MagicHandyShapes(
    val card: RoundedCornerShape = RoundedCornerShape(10.dp),
    val control: RoundedCornerShape = RoundedCornerShape(6.dp),
    val small: RoundedCornerShape = RoundedCornerShape(4.dp),
    val sheet: RoundedCornerShape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
    val personaBubble: RoundedCornerShape =
        RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomEnd = 10.dp, bottomStart = 4.dp),
    val ownBubble: RoundedCornerShape =
        RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomEnd = 4.dp, bottomStart = 10.dp),
)

/** A hairline's width, for drawing outside composition. The same as [MagicHandyDimens.hairline]. */
internal val HairlineWidth: Dp = 1.dp

/**
 * Spacing and sizes. Screens take every measurement from here (plan §5.5).
 *
 * Spacing runs from [xxs] to [xxl] on the boards' 4 dp rhythm, with the four half steps the boards
 * also use: [tight], [snug], [cozy] and [roomy]. A few insets the boards repeat have their own
 * names. Sizes are named for the part they measure.
 */
@Immutable
data class MagicHandyDimens(
    /** 2 dp: a line and its detail. */
    val xxs: Dp = 2.dp,
    /** 3 dp: lines that belong together, such as a name and its status. */
    val tight: Dp = 3.dp,
    val xs: Dp = 4.dp,
    /** 6 dp: a dot or small icon and its word, and compact stacks. */
    val snug: Dp = 6.dp,
    val s: Dp = 8.dp,
    /** 10 dp: items in a row, and the inset of banners and two-line rows. */
    val cozy: Dp = 10.dp,
    val m: Dp = 12.dp,
    /** 14 dp: inside buttons and toasts, and between chat messages. */
    val roomy: Dp = 14.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 28.dp,
    /** The vertical inset of a Coyote safety note. */
    val noteInset: Dp = 5.dp,
    /** Between the fields and cards of a model setup page. */
    val formGap: Dp = 9.dp,
    /** The vertical inset of chat bubbles and Stop receipt rows. */
    val bubbleInset: Dp = 9.dp,
    /** The vertical inset of Run interrupted's rows. */
    val recoveryRowInset: Dp = 11.dp,
    /** The vertical inset of the composer's text, which centres one line in the field. */
    val composerInset: Dp = 11.dp,
    /** How far Back sits outside a page's edge, so its glyph lines up with the title. */
    val backOffset: Dp = 8.dp,
    /** How far a sheet's Back sits outside the sheet's edge. */
    val sheetBackOffset: Dp = 12.dp,
    /** How far a sheet's Close sits outside the sheet's edge. */
    val sheetCloseOffset: Dp = 10.dp,
    val hairline: Dp = HairlineWidth,
    /** The outline of a ring-shaped status dot. */
    val ringStroke: Dp = 1.5.dp,
    /** The outline of a radio mark, and the scan spinner's stroke. */
    val markStroke: Dp = 2.dp,
    val touch: Dp = 48.dp,
    val control: Dp = 48.dp,
    val compactControl: Dp = 40.dp,
    val tab: Dp = 52.dp,
    val stopRow: Dp = 60.dp,
    /** A segment inside its well. */
    val segment: Dp = 42.dp,
    /** A segment with a note under its label, such as a suggested capability. */
    val noteSegment: Dp = 44.dp,
    /** The Autopilot card's heading on Live. */
    val headingRow: Dp = 22.dp,
    /** One device's output on Live. */
    val readoutRow: Dp = 26.dp,
    /** One line of Recent changes. */
    val eventRow: Dp = 28.dp,
    val stepperRow: Dp = 50.dp,
    /** A sheet's header and its list rows. */
    val sheetRow: Dp = 52.dp,
    /** A row with a title and a detail line, such as a saved connection. */
    val listRow: Dp = 56.dp,
    /** A device row, with its tile and status line. */
    val deviceRow: Dp = 64.dp,
    /** A stepper's value column. */
    val readoutWidth: Dp = 52.dp,
    /** The palette choice; any narrower and it wraps under its label. */
    val paletteWidth: Dp = 240.dp,
    val dot: Dp = 8.dp,
    /** A value menu's caret. */
    val menuCaret: Dp = 12.dp,
    /** An icon set inline with text: chevrons and the warning sign. */
    val inlineIcon: Dp = 14.dp,
    val smallIcon: Dp = 16.dp,
    /** The icon inside a control: stepper keys, Send and the strip's chevron. */
    val actionIcon: Dp = 18.dp,
    val icon: Dp = 20.dp,
    /** A radio mark, and the dot inside it when selected. */
    val radioMark: Dp = 22.dp,
    val radioDot: Dp = 10.dp,
    val checkBox: Dp = 24.dp,
    val deviceTile: Dp = 36.dp,
    val pageTile: Dp = 44.dp,
    /** The brand mark on Before you start. */
    val brandMark: Dp = 52.dp,
    val switchWidth: Dp = 44.dp,
    val switchHeight: Dp = 26.dp,
    val switchThumb: Dp = 20.dp,
    /** The gap around a switch's thumb inside its track. */
    val switchInset: Dp = 3.dp,
    /** The bar between the strip's readout and its countdown. */
    val dividerHeight: Dp = 16.dp,
    /** The streaming caret after a reply's last word. */
    val caretWidth: Dp = 2.dp,
    val caretHeight: Dp = 16.dp,
    /** The narrowest a More options menu is. */
    val menuWidth: Dp = 180.dp,
    /** One segment of setup's progress bar. */
    val progressBar: Dp = 4.dp,
    /** The done or to-do mark beside each line of You're set. */
    val readyMark: Dp = 24.dp,
    /** A personality description's field before anything is typed: about three lines. */
    val descriptionField: Dp = 84.dp,
    val bubbleMaxFraction: Float = 0.86f,
)

private val LocalColors = staticCompositionLocalOf { colorsFor(Palette.SteelAzure) }
private val LocalType = staticCompositionLocalOf { Type }
private val LocalShapes = staticCompositionLocalOf { MagicHandyShapes() }
private val LocalDimens = staticCompositionLocalOf { MagicHandyDimens() }

/** Access to the current tokens: `MagicHandyTheme.colors`, `.type`, `.shapes` and `.dimens`. */
object MagicHandyTheme {
    val colors: MagicHandyColors
        @Composable get() = LocalColors.current

    val type: MagicHandyType
        @Composable get() = LocalType.current

    val shapes: MagicHandyShapes
        @Composable get() = LocalShapes.current

    val dimens: MagicHandyDimens
        @Composable get() = LocalDimens.current
}

/** Provides the tokens for [palette], and a matching Material scheme for the few M3 parts used. */
@Composable
fun MagicHandyTheme(palette: Palette, content: @Composable () -> Unit) {
    val colors = colorsFor(palette)
    val scheme =
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.ground,
            onBackground = colors.text,
            surface = colors.raised,
            onSurface = colors.text,
            onSurfaceVariant = colors.muted,
            outline = colors.strongLine,
            error = colors.danger,
        )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalColors provides colors,
            LocalContentColor provides colors.text,
            LocalTextStyle provides Type.body,
            content = content,
        )
    }
}

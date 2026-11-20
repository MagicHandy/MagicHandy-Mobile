package com.magichandy.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.magichandy.mobile.core.api.Palette

/**
 * The colour tokens every screen draws with (plan §14.1). Neutrals change with the palette; the
 * semantic colours (accent, start, ok, warn, danger and Stop) stay fixed in all three.
 * `PaletteContrastTest` holds every palette to the desktop's contrast rules (ADR 0073).
 *
 * @property ground the app background.
 * @property well sunken areas: the Now playing strip, text fields and segmented controls.
 * @property chrome the bottom bar behind the tabs and Stop.
 * @property surface cards, the deck and sheets.
 * @property raised bubbles, stepper keys and device tiles.
 * @property line hairlines between rows.
 * @property strongLine outlines of controls.
 * @property fieldLine outlines of text fields.
 * @property faint chevrons and the idle dot.
 */
@Immutable
data class MagicHandyColors(
    val ground: Color,
    val well: Color,
    val chrome: Color,
    val surface: Color,
    val raised: Color,
    val ownBubble: Color,
    val ownBubbleLine: Color,
    val line: Color,
    val strongLine: Color,
    val fieldLine: Color,
    val text: Color,
    val textSecondary: Color,
    val muted: Color,
    val faint: Color,
    val toast: Color,
    val accentContainer: Color,
) {
    val accent: Color = Accent
    val onAccent: Color = OnAccent
    val start: Color = Start
    val onStart: Color = OnStart
    val ok: Color = Ok
    val warn: Color = Warn
    val danger: Color = Danger
    val dangerText: Color = DangerText
    val dangerContainer: Color = Danger.copy(alpha = 0.12f)
    val dangerLine: Color = Danger.copy(alpha = 0.45f)
    val warnLine: Color = Warn.copy(alpha = 0.45f)
    val stop: Color = Stop
    val onStop: Color = Color.White
    val scrim: Color = Color.Black.copy(alpha = 0.72f)
}

private val Accent = Color(0xFF5B9DD9)
private val OnAccent = Color(0xFF0B131C)
private val Start = Color(0xFF287D42)
private val OnStart = Color(0xFFF2FBF4)
private val Ok = Color(0xFF4FC06D)
private val Warn = Color(0xFFD8B66A)
private val Danger = Color(0xFFD6453F)
private val DangerText = Color(0xFFF0A39F)
private val Stop = Color(0xFFC43C36)

private val SteelAzure =
    MagicHandyColors(
        ground = Color(0xFF0E0F11),
        well = Color(0xFF0B0C0E),
        chrome = Color(0xFF121417),
        surface = Color(0xFF16181B),
        raised = Color(0xFF1D2024),
        ownBubble = Color(0xFF1B2634),
        ownBubbleLine = Color(0xFF2C3E52),
        line = Color(0xFF262A30),
        strongLine = Color(0xFF353B43),
        fieldLine = Color(0xFF4B5159),
        text = Color(0xFFE9E7E2),
        textSecondary = Color(0xFFC9C6C0),
        muted = Color(0xFF9AA1A9),
        faint = Color(0xFF6B7178),
        toast = Color(0xFF24282E),
        accentContainer = Accent.copy(alpha = 0.15f),
    )

/**
 * True black for OLED screens, which switch black pixels off: it saves power and doesn't glow in a
 * dark room. The ground, wells and the bar behind the tabs and Stop are pure black. Cards sit just
 * above black so groups still read, and keys, bubbles and notices lift a step further. The text and
 * line greys follow Steel Azure's.
 */
private val OledBlack =
    MagicHandyColors(
        ground = Color(0xFF000000),
        well = Color(0xFF000000),
        chrome = Color(0xFF000000),
        surface = Color(0xFF0B0C0D),
        raised = Color(0xFF16181B),
        ownBubble = Color(0xFF0F1924),
        ownBubbleLine = Color(0xFF233548),
        line = Color(0xFF22252A),
        strongLine = Color(0xFF33373E),
        fieldLine = Color(0xFF4B5159),
        text = Color(0xFFE9E7E2),
        textSecondary = Color(0xFFC9C6C0),
        muted = Color(0xFFA2A8AF),
        faint = Color(0xFF6B7178),
        toast = Color(0xFF1D2024),
        accentContainer = Accent.copy(alpha = 0.16f),
    )

private val HighContrast =
    MagicHandyColors(
        ground = Color(0xFF000000),
        well = Color(0xFF000000),
        chrome = Color(0xFF000000),
        surface = Color(0xFF000000),
        raised = Color(0xFF000000),
        ownBubble = Color(0xFF000000),
        ownBubbleLine = Accent,
        line = Color(0xFF8A8A8A),
        strongLine = Color(0xFFA9A9A9),
        fieldLine = Color(0xFFE5E5E5),
        text = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFE5E5E5),
        muted = Color(0xFFE5E5E5),
        faint = Color(0xFFA9A9A9),
        toast = Color(0xFF000000),
        accentContainer = Accent.copy(alpha = 0.28f),
    )

/** The token set for [palette]. */
fun colorsFor(palette: Palette): MagicHandyColors =
    when (palette) {
        Palette.SteelAzure -> SteelAzure
        Palette.OledBlack,
        Palette.Lowlight -> OledBlack
        Palette.HighContrast -> HighContrast
    }

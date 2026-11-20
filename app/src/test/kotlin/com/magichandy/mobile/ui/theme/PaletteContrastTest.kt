package com.magichandy.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.magichandy.mobile.core.api.Palette
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every palette meets the desktop's contrast rules (ADR 0073), checked with the WCAG 2 ratio:
 * - 4.5:1 for text, secondary text and muted text on every ground they're drawn on;
 * - 4.5:1 for ink on the accent, on Start and on Stop, and for warning and danger text;
 * - 3:1 for the accent and the status colours against every ground, as marks and outlines.
 */
internal class PaletteContrastTest {
    @TestFactory
    fun `every palette meets the contrast rules`(): List<DynamicTest> =
        Palette.entries.flatMap { palette ->
            val colors = colorsFor(palette)
            val grounds =
                mapOf(
                    "ground" to colors.ground,
                    "well" to colors.well,
                    "chrome" to colors.chrome,
                    "surface" to colors.surface,
                    "raised" to colors.raised,
                    "toast" to colors.toast,
                    "own bubble" to colors.ownBubble,
                    "accent container" to colors.accentContainer.compositeOver(colors.surface),
                )
            val text =
                mapOf(
                    "text" to colors.text,
                    "secondary text" to colors.textSecondary,
                    "muted text" to colors.muted,
                    "warning text" to colors.warn,
                    "danger text" to colors.dangerText,
                )
            val marks = mapOf("accent" to colors.accent, "ok" to colors.ok, "warn" to colors.warn)
            val inks =
                listOf(
                    Triple("ink on the accent", colors.onAccent, colors.accent),
                    Triple("ink on Start", colors.onStart, colors.start),
                    Triple("ink on Stop", colors.onStop, colors.stop),
                )
            grounds.flatMap { (groundName, ground) ->
                text.map { (name, color) ->
                    check(palette, "$name on $groundName", color, ground, 4.5)
                } +
                    marks.map { (name, color) ->
                        check(palette, "$name on $groundName", color, ground, 3.0)
                    }
            } + inks.map { (name, ink, ground) -> check(palette, name, ink, ground, 4.5) }
        }

    private fun check(
        palette: Palette,
        what: String,
        color: Color,
        ground: Color,
        minimum: Double,
    ) =
        DynamicTest.dynamicTest("$palette: $what") {
            val ratio = contrast(color, ground)
            assertTrue(ratio >= minimum) {
                "$palette: $what is %.2f:1, below %.1f:1".format(ratio, minimum)
            }
        }

    /** The WCAG 2 contrast ratio between two opaque colours. */
    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }
}

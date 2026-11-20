package com.magichandy.mobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's line icons, taken from the design boards' 16-unit SVGs. Each is tinted where it is
 * used, so the paths are black.
 */
object AppIcons {
    val Chat: ImageVector by lazy { outline("chat", "M2.5 3.5h11v7h-6l-3 2.5v-2.5h-2z") }

    val Devices: ImageVector by lazy {
        outline(
            "devices",
            "M3.5 3h2a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-2A1.5 1.5 0 0 1 2 11.5v-7A1.5 1.5 0 0 1 3.5 3z",
            "M10.5 5h2a1.5 1.5 0 0 1 1.5 1.5v5a1.5 1.5 0 0 1-1.5 1.5h-2A1.5 1.5 0 0 1 9 11.5v-5A1.5 1.5 0 0 1 10.5 5z",
        )
    }

    val Settings: ImageVector by lazy {
        outline(
            "settings",
            "M2 4.5h2.2M7.8 4.5H14M2 11.5h6.7M12.3 11.5H14",
            "M6 2.7a1.8 1.8 0 1 0 0 3.6a1.8 1.8 0 1 0 0-3.6z",
            "M10.5 9.7a1.8 1.8 0 1 0 0 3.6a1.8 1.8 0 1 0 0-3.6z",
        )
    }

    val ChevronDown: ImageVector by lazy { outline("chevron_down", "M4 6l4 4 4-4") }
    val ChevronUp: ImageVector by lazy { outline("chevron_up", "M4 10l4-4 4 4") }
    val ChevronRight: ImageVector by lazy { outline("chevron_right", "M6 4l4 4-4 4") }
    val Back: ImageVector by lazy { outline("back", "M10 3L5 8l5 5") }
    val Close: ImageVector by lazy { outline("close", "M4 4l8 8M12 4l-8 8") }
    val Send: ImageVector by lazy { outline("send", "M8 13V3M3.5 7.5L8 3l4.5 4.5") }
    val Check: ImageVector by lazy { outline("check", "M3.5 8.5l3 3 6-7") }
    val Plus: ImageVector by lazy { outline("plus", "M8 3.5v9M3.5 8h9") }
    val Minus: ImageVector by lazy { outline("minus", "M3.5 8h9") }
    val Warning: ImageVector by lazy {
        outline("warning", "M8 2L1.5 13.5h13z", "M8 6.5v3.5M8 11.6v.4")
    }

    /** The Handy: a stroker body with its sleeve line. */
    val Handy: ImageVector by lazy {
        outline(
            "handy",
            "M7 1.5h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z",
            "M5 6h6",
        )
    }

    /** A vibrator with its buzz marks. */
    val Vibrator: ImageVector by lazy {
        outline(
            "vibrator",
            "M8 2a2 2 0 0 1 2 2v8a2 2 0 0 1-4 0V4a2 2 0 0 1 2-2z",
            "M3 6c-1 1.3-1 2.7 0 4M13 6c1 1.3 1 2.7 0 4",
        )
    }

    /** The Coyote's e-stim bolt. */
    val Coyote: ImageVector by lazy { outline("coyote", "M9 1.5L3.5 9H8l-1 5.5L12.5 7H8z") }

    /** The Autoblow Ultra's cylinder. */
    val Ultra: ImageVector by lazy {
        outline(
            "ultra",
            "M7 2h2a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V5a3 3 0 0 1 3-3z",
            "M4 6h8M4 10h8",
        )
    }

    /** The VacuGlide's ribbed body. */
    val VacuGlide: ImageVector by lazy {
        outline(
            "vacuglide",
            "M7 2h2a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V5a3 3 0 0 1 3-3z",
            "M6 5h4M6 8h4M6 11h4",
        )
    }

    val Stop: ImageVector by lazy {
        filled(
            "stop",
            "M4.5 3h7a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 3 11.5v-7A1.5 1.5 0 0 1 4.5 3z",
        )
    }

    private fun outline(name: String, vararg paths: String): ImageVector =
        build(name, paths.toList(), isFilled = false)

    private fun filled(name: String, vararg paths: String): ImageVector =
        build(name, paths.toList(), isFilled = true)

    private fun build(name: String, paths: List<String>, isFilled: Boolean): ImageVector {
        val builder =
            ImageVector.Builder(
                name = name,
                defaultWidth = 16.dp,
                defaultHeight = 16.dp,
                viewportWidth = 16f,
                viewportHeight = 16f,
            )
        for (path in paths) {
            builder.addPath(
                pathData = addPathNodes(path),
                fill = if (isFilled) SolidColor(Color.Black) else null,
                stroke = if (isFilled) null else SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}

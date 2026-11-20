package com.magichandy.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * [text] with each of [readouts] set in the mono face, as the boards set paces, timers, latencies
 * and signal strengths. [isStrong] also makes the readouts semibold.
 */
@Composable
fun withMonoReadouts(
    text: String,
    vararg readouts: String,
    isStrong: Boolean = false,
): AnnotatedString {
    val mono = MagicHandyTheme.type.mono.fontFamily
    return buildAnnotatedString {
        append(text)
        for (readout in readouts) {
            if (readout.isEmpty()) continue
            val start = text.indexOf(readout)
            if (start < 0) continue
            addStyle(
                SpanStyle(
                    fontFamily = mono,
                    fontWeight = if (isStrong) FontWeight.SemiBold else null,
                ),
                start,
                start + readout.length,
            )
        }
    }
}

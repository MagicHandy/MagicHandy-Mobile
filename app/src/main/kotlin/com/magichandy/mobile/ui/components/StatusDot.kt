package com.magichandy.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** How a status dot reads: green ok, amber warn, red danger, accent, idle, faint or a ring. */
enum class DotTone {
    Ok,
    Warn,
    Danger,
    Accent,
    Idle,
    Faint,
    Ring,
}

/** A status dot. It always sits next to a word, never alone (system design §8). */
@Composable
fun StatusDot(tone: DotTone, modifier: Modifier = Modifier) {
    val colors = MagicHandyTheme.colors
    val base = modifier.size(MagicHandyTheme.dimens.dot).clip(CircleShape)
    val fill =
        when (tone) {
            DotTone.Ok -> colors.ok
            DotTone.Warn -> colors.warn
            DotTone.Danger -> colors.danger
            DotTone.Accent -> colors.accent
            DotTone.Idle -> colors.muted
            DotTone.Faint -> colors.faint
            DotTone.Ring -> Color.Transparent
        }
    if (tone == DotTone.Ring) {
        Box(base.border(MagicHandyTheme.dimens.ringStroke, colors.muted, CircleShape))
    } else {
        Box(base.background(fill))
    }
}

/** A dot and its word. */
@Composable
fun StatusLabel(
    tone: DotTone,
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    textStyle: TextStyle = MagicHandyTheme.type.small,
) {
    StatusLabel(tone, AnnotatedString(text), modifier, color, textStyle)
}

/** A dot and styled text, for status lines that mix weights or set readouts in mono. */
@Composable
fun StatusLabel(
    tone: DotTone,
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color? = null,
    textStyle: TextStyle = MagicHandyTheme.type.small,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(tone)
        Spacer(Modifier.width(MagicHandyTheme.dimens.snug))
        Text(text, style = textStyle, color = color ?: MagicHandyTheme.colors.textSecondary)
    }
}

package com.magichandy.mobile.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.ReactionStyle
import com.magichandy.mobile.core.api.ReplyLength

/**
 * Localized words for a persona's personality. The definitions match the desktop app's, so a person
 * reads the same meaning on both.
 */
object PersonaWords {
    @Composable
    fun level(level: Explicitness): String =
        stringResource(
            when (level) {
                Explicitness.Utility -> R.string.explicit_utility
                Explicitness.Warm -> R.string.explicit_warm
                Explicitness.Intimate -> R.string.explicit_intimate
                Explicitness.Explicit -> R.string.explicit_explicit
            }
        )

    @Composable
    fun levelDefinition(level: Explicitness): String =
        stringResource(
            when (level) {
                Explicitness.Utility -> R.string.explicit_utility_desc
                Explicitness.Warm -> R.string.explicit_warm_desc
                Explicitness.Intimate -> R.string.explicit_intimate_desc
                Explicitness.Explicit -> R.string.explicit_explicit_desc
            }
        )

    @Composable
    fun style(style: ReactionStyle): String =
        stringResource(
            when (style) {
                ReactionStyle.Neutral -> R.string.reaction_neutral
                ReactionStyle.Playful -> R.string.reaction_playful
                ReactionStyle.Tender -> R.string.reaction_tender
                ReactionStyle.Teasing -> R.string.reaction_teasing
                ReactionStyle.Dominant -> R.string.reaction_dominant
                ReactionStyle.Submissive -> R.string.reaction_submissive
            }
        )

    @Composable
    fun styleDefinition(style: ReactionStyle): String =
        stringResource(
            when (style) {
                ReactionStyle.Neutral -> R.string.reaction_neutral_desc
                ReactionStyle.Playful -> R.string.reaction_playful_desc
                ReactionStyle.Tender -> R.string.reaction_tender_desc
                ReactionStyle.Teasing -> R.string.reaction_teasing_desc
                ReactionStyle.Dominant -> R.string.reaction_dominant_desc
                ReactionStyle.Submissive -> R.string.reaction_submissive_desc
            }
        )

    @Composable
    fun replyLength(length: ReplyLength): String =
        stringResource(
            when (length) {
                ReplyLength.Short -> R.string.reply_short
                ReplyLength.Detailed -> R.string.reply_detailed
            }
        )

    /** The level and style on one line, such as "Warm · Playful". */
    @Composable
    fun line(personality: Personality): String =
        stringResource(
            R.string.persona_line,
            level(personality.explicitness),
            style(personality.style),
        )
}

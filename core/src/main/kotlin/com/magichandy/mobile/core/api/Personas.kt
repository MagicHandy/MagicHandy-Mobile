package com.magichandy.mobile.core.api

import java.text.BreakIterator

/**
 * How explicit a persona's replies may be (plan §12, decided on 2026-11-20). Each level selects a
 * fixed instruction the code owns, as on the desktop. It shapes wording only: limits, what the
 * model may control, Autopilot and Stop are the same at every level.
 */
public enum class Explicitness {
    /** No sexual content; replies stay practical. The default, as on the desktop. */
    Utility,

    /** Flirty and suggestive, never graphic. */
    Warm,

    /** A sensual partner voice with indirect language. */
    Intimate,

    /** Direct, graphic sexual language. */
    Explicit,
}

/**
 * Who leads the conversation and how replies carry themselves. Each style is a fixed instruction
 * the code owns that never mentions motion, the device or JSON. [Neutral] adds nothing at all.
 */
public enum class ReactionStyle {
    Neutral,
    Playful,
    Tender,
    Teasing,
    Dominant,
    Submissive,
}

/** How long replies may run. Detailed raises the reply's output allowance; motion is unaffected. */
public enum class ReplyLength {
    Short,
    Detailed,
}

/**
 * The parts of a persona a person can change. None of them changes limits, what the model may
 * control, Autopilot or Stop.
 *
 * @property description how the persona speaks and behaves, at most [PersonaLimits.DESCRIPTION]
 *   characters as [characterCount] counts them. It is sent to the model, quoted as data, only at
 *   Warm, Intimate and Explicit. At Utility it is kept but not sent.
 */
@kotlinx.serialization.Serializable
public data class Personality(
    public val explicitness: Explicitness = Explicitness.Utility,
    public val style: ReactionStyle = ReactionStyle.Neutral,
    public val description: String = "",
    public val replyLength: ReplyLength = ReplyLength.Short,
)

/** Bounds the core enforces and the UI shows, in characters as [characterCount] counts them. */
public object PersonaLimits {
    /** The longest persona name. */
    public const val NAME: Int = 60

    /** The longest personality description. */
    public const val DESCRIPTION: Int = 500

    /** The most personas a person can make, besides the built-in one. */
    public const val PERSONAS: Int = 200
}

/**
 * Counts [text] the way a person reads it: once per user-perceived character (grapheme cluster), so
 * an emoji, an accented letter or a Japanese character each count once. The core and the UI both
 * use it, so the counter on screen and the core's validation agree.
 */
public fun characterCount(text: String): Int {
    val boundaries = BreakIterator.getCharacterInstance()
    boundaries.setText(text)
    var count = 0
    while (boundaries.next() != BreakIterator.DONE) count++
    return count
}

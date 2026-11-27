package com.magichandy.mobile.core.api

/**
 * How long an OpenRouter model thinks before it replies, lowest first, as OpenRouter's
 * `reasoning.effort` names it. Off isn't a level: a connection with reasoning off has
 * [ModelConnection.isReasoningOn] false.
 */
public enum class ReasoningEffort {
    Minimal,
    Low,
    Medium,
    High,
    ExtraHigh,
    Max,
}

/**
 * The reasoning an OpenRouter model offers (docs/openrouter-design.md §4.3).
 *
 * @property levels the levels OpenRouter lists for the model, lowest first. Empty when it can only
 *   turn reasoning on or off.
 * @property isRequired true when the model always reasons, so reasoning can't be turned off.
 * @property defaultLevel the model's own level, when OpenRouter names one.
 */
public data class ReasoningOptions(
    public val levels: List<ReasoningEffort>,
    public val isRequired: Boolean,
    public val defaultLevel: ReasoningEffort? = null,
) {
    /**
     * The reasoning a connection asking for [isOn] at [level] gets on this model. A model that
     * always reasons can't be turned off, so it starts at its lowest level. A level the model
     * doesn't list becomes the nearest one it does, the lower on a tie, and a model that only turns
     * reasoning on or off gets no level.
     */
    public fun settle(isOn: Boolean, level: ReasoningEffort?): ReasoningChoice {
        if (!isOn && !isRequired) return ReasoningChoice(false, null)
        // The lowest level replies soonest.
        if (!isOn) return ReasoningChoice(true, offered.firstOrNull())
        if (level == null || offered.isEmpty()) {
            return ReasoningChoice(true, level.takeIf { offered.isNotEmpty() })
        }
        if (level in offered) return ReasoningChoice(true, level)
        return ReasoningChoice(true, offered.minBy { kotlin.math.abs(it.ordinal - level.ordinal) })
    }

    /**
     * The listed levels the app offers: up to High, since higher levels can need more room than a
     * reply has (docs/openrouter-design.md §4.3). A model that lists only higher levels offers its
     * lowest one.
     */
    public val offered: List<ReasoningEffort>
        get() = levels.filter { it <= ReasoningEffort.High }.ifEmpty { levels.take(1) }
}

/**
 * Off, or on at a level.
 *
 * @property level null while on lets the model choose its own level.
 */
public data class ReasoningChoice(public val isOn: Boolean, public val level: ReasoningEffort?)

/**
 * A model in OpenRouter's catalog that can chat in MagicHandy: it replies in MagicHandy's format,
 * on a host that keeps no data.
 *
 * @property id OpenRouter's model ID, such as `deepseek/deepseek-v4.1-flash-1017`.
 * @property name the catalog's name without its author prefix, such as "DeepSeek V4.1 Flash 1017".
 * @property contextTokens the most the model takes in and writes together, in tokens.
 * @property reasoning null when the model doesn't reason.
 * @property inputMicrosPerMillion what a million tokens sent cost, in millionths of a US dollar;
 *   null when OpenRouter doesn't say.
 * @property outputMicrosPerMillion what a million tokens written cost, in millionths of a US
 *   dollar; null when OpenRouter doesn't say.
 */
public data class RouterModel(
    public val id: String,
    public val name: String,
    public val contextTokens: Int,
    public val reasoning: ReasoningOptions?,
    public val inputMicrosPerMillion: Long?,
    public val outputMicrosPerMillion: Long?,
) {
    /** True when OpenRouter charges nothing for the model. */
    public val isFree: Boolean
        get() = inputMicrosPerMillion == 0L && outputMicrosPerMillion == 0L

    /**
     * About what 100 replies cost, in millionths of a US dollar, before reasoning: each sends 4,000
     * tokens and gets 500 back. The desktop measured a median prompt of 3,700 tokens.
     */
    public val hundredRepliesMicros: Long?
        get() {
            val input = inputMicrosPerMillion ?: return null
            val output = outputMicrosPerMillion ?: return null
            return (REPLY_INPUT_TOKENS * input + REPLY_OUTPUT_TOKENS * output) * 100 / 1_000_000
        }

    private companion object {
        const val REPLY_INPUT_TOKENS = 4_000L
        const val REPLY_OUTPUT_TOKENS = 500L
    }
}

/** OpenRouter's catalog, as the app last loaded it for the OpenRouter pages. */
public sealed interface RouterCatalog {
    /** Not asked for yet. */
    public data object Idle : RouterCatalog

    public data object Loading : RouterCatalog

    /** [models] in OpenRouter's order, most popular first. */
    public data class Loaded(public val models: List<RouterModel>) : RouterCatalog

    /** OpenRouter couldn't be reached. Opening an OpenRouter page again retries. */
    public data object Failed : RouterCatalog
}

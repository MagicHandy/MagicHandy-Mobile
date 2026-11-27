package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.RouterModel
import com.magichandy.mobile.core.api.Secret

/** What OpenRouter said about a key. */
public enum class KeyCheck {
    Accepted,
    Rejected,
    /** OpenRouter couldn't be reached, or answered with something else. */
    Unreachable,
}

/**
 * OpenRouter's catalog and key check, for setting up a connection (docs/openrouter-design.md §4).
 * Neither call sends chat content, and neither spends credits.
 */
public interface OpenRouterAccess {
    /**
     * The models that can chat in MagicHandy, most popular first, from a copy at most 15 minutes
     * old.
     *
     * @throws java.io.IOException when OpenRouter can't be reached or sends an unreadable catalog.
     */
    public suspend fun models(): List<RouterModel>

    /** Asks OpenRouter whether it accepts [key]. */
    public suspend fun check(key: Secret): KeyCheck
}

/** The API key must fit a single HTTP authorization header. */
public fun Secret.isValidOpenRouterKey(): Boolean =
    reveal().let { value ->
        value.length in 1..2048 && value.all { it.code in 33..126 }
    }

package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.SignInFailure

/**
 * Text-only input roles for the first hosted path. Motion proposals require separate validation.
 */
public enum class TextRole {
    Developer,
    User,
    Assistant,
}

/** One complete message in a bounded text-only conversation. */
public data class TextMessage(public val role: TextRole, public val text: String)

/** A streamed turn has exactly one terminal outcome. Only a completed reply can be persisted. */
public sealed interface TextResult {
    /** Terminal success with the complete buffered reply. */
    public data class Completed(public val text: String) : TextResult

    /** The model declined the turn. */
    public data object Refused : TextResult

    /** Completion, authorization or delivery could not be established. */
    public data object Interrupted : TextResult
}

/** Provider for text turns. Cancellation must abort transport I/O and discard partial output. */
public fun interface ModelProvider {
    /** Emit provisional deltas to [preview], then return an authoritative terminal result. */
    public suspend fun stream(messages: List<TextMessage>, preview: (String) -> Unit): TextResult
}

/** The app owns the redirect listener or app-link receiver and launches a system browser. */
public interface BrowserLauncher {
    /** Exact redirect URI reserved for this receiver before opening the browser. */
    public val redirectUri: String

    /**
     * Return the redirect URI, including its sensitive query, once. Null means user cancellation.
     * Cancel the receiver when the owning coroutine is cancelled.
     */
    public suspend fun openAndAwait(authorization: Secret): Secret?
}

/** Sign in without exposing tokens to UI state. Coroutine cancellation cancels the transaction. */
public fun interface ModelSignIn {
    /** Null on a durably stored, verified account. Otherwise returns the redacted cause. */
    public suspend fun signIn(): SignInFailure?
}

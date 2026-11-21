package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.SignInState
import kotlin.time.Duration.Companion.minutes

/** Total time available for interactive ChatGPT consent, including returning to the app. */
public val signInWindow: kotlin.time.Duration = 5.minutes

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

    /** A terminal provider failure with an allowlisted explanation for the user. */
    public data class Failed(public val error: ProviderReplyFailure) : TextResult
}

/** Provider for text turns. Cancellation must abort transport I/O and discard partial output. */
public fun interface ModelProvider {
    /** Emit provisional deltas to [preview], then return an authoritative terminal result. */
    public suspend fun stream(messages: List<TextMessage>, preview: (String) -> Unit): TextResult
}

/**
 * An in-process demonstration model can label its synthetic replies with their captured speaker.
 * Network providers use the composed messages alone.
 */
public interface SimulatedSpeaker : ModelProvider {
    /** Return an immutable speaker for this turn, including any repair attempt. */
    public fun speakingAs(name: String, level: Explicitness): ModelProvider
}

/** The app owns the redirect listener or app-link receiver and launches a system browser. */
public interface BrowserLauncher {
    /** Close any pending single-use receiver, including if request construction fails. */
    public fun close() {}

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
    public suspend fun signIn(): SignInState.Failed?
}

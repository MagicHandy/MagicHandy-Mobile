package com.magichandy.mobile.core.api

import kotlin.time.Duration

/**
 * Model connections and their roles.
 *
 * @property chatConnection the connection chat uses; null until one is set up.
 * @property plannerConnection the connection Autopilot plans with; null means "Same as chat".
 */
public data class ModelsState(
    public val connections: List<ModelConnection>,
    public val chatConnection: ConnectionId?,
    public val plannerConnection: ConnectionId?,
    public val serverCheck: ServerCheck,
    public val signIn: SignInState,
    /** Local connection explicitly authorized for a hosted refusal retry, or null for Off. */
    public val refusalFallback: ConnectionId? = null,
    /** Advances after a new or returning ChatGPT sign-in is saved by this process. */
    public val completedSignIns: Long = 0,
) {
    /** The active ChatGPT connection needs a fresh browser sign-in before it can serve chat. */
    public val chatNeedsSignIn: Boolean
        get() = connections.any {
            it.id == chatConnection &&
                it.provider == ProviderKind.ChatGpt &&
                it.status == ConnectionStatus.NeedsSignIn
        }
}

/**
 * A saved model connection.
 *
 * @property model the full model name, such as "GPT-6.1 Terra" or "gemma4:12b".
 * @property shortName the name the UI shows in tight places, such as "Terra".
 * @property isEncrypted false for plain HTTP to a server on the local network.
 */
@kotlinx.serialization.Serializable
public data class ModelConnection(
    public val id: ConnectionId,
    public val name: String,
    public val provider: ProviderKind,
    public val model: String,
    public val shortName: String,
    public val capability: CapabilityLevel,
    public val isReasoningOn: Boolean,
    public val isEncrypted: Boolean,
    public val status: ConnectionStatus,
)

/** The v1 providers (plan §9.1). */
public enum class ProviderKind {
    ChatGpt,
    OpenRouter,
    Ollama,
    LlamaCpp,
    OpenAiCompatible,
}

/** The three capability levels the UI shows. Advanced picks its no-reasoning contract itself. */
public enum class CapabilityLevel {
    Simple,
    Intermediate,
    Advanced,
}

/** Whether a connection can serve requests now. */
public enum class ConnectionStatus {
    Ready,
    Checking,
    Unreachable,
    NeedsSignIn,
}

/** Checking a server on the local network by its address. There is no scan (plan §15). */
public sealed interface ServerCheck {
    public data object Idle : ServerCheck

    public data class Checking(public val address: String) : ServerCheck

    /**
     * The server answered.
     *
     * @property testedAt the level this model is tested at, when it is a known model.
     * @property contextNeeded the context, in tokens, that [suggested] needs.
     * @property contextAllowed the context, in tokens, the server allows, when it says.
     * @property repliedIn the round trip for a completed test that met the requested reply
     *   contract.
     * @property testFailure why the selected model failed its test on an otherwise discovered
     *   server.
     */
    public data class Found(
        public val address: String,
        public val provider: ProviderKind,
        public val isEncrypted: Boolean,
        public val models: List<String>,
        public val selectedModel: String,
        public val suggested: CapabilityLevel,
        public val testedAt: CapabilityLevel?,
        public val contextNeeded: Int,
        public val contextAllowed: Int?,
        public val repliedIn: Duration?,
        public val testFailure: ServerCheckFailure? = null,
    ) : ServerCheck

    public data class Failed(public val address: String, public val cause: ServerCheckFailure) :
        ServerCheck
}

/** Why a server check failed. */
public enum class ServerCheckFailure {
    InvalidAddress,
    Unreachable,
    NotAModelServer,
    NoModels,
    /** Plain HTTP is allowed only to private, link-local and loopback addresses. */
    NotPrivateAddress,
    /** A previously selected model is no longer in the server's catalog. */
    ModelUnavailable,
    /** The selected model explicitly refused the inert readiness request. */
    ProviderRefused,
    /** A discovered model did not complete a reply matching the requested structured format. */
    IncompleteReply,
}

/** ChatGPT sign-in through the system browser. */
public sealed interface SignInState {
    public data object Idle : SignInState

    public data object WaitingForBrowser : SignInState

    /**
     * [stage], [httpStatus] and [networkIssue] identify the failed operation using only public
     * categories. HTTP status is available when the server replied.
     */
    public data class Failed(
        public val cause: SignInFailure,
        public val stage: SignInStage? = null,
        public val httpStatus: Int? = null,
        public val networkIssue: SignInNetworkIssue? = null,
    ) : SignInState {
        init {
            require(httpStatus == null || httpStatus in 100..599)
        }
    }
}

/** Which connection step failed before a complete response arrived. */
public enum class SignInNetworkIssue {
    NameResolution,
    SecureConnection,
    Timeout,
    ConnectionLost,
}

/** The part of the browser sign-in for which the app has a result. */
public enum class SignInStage {
    Discovery,
    BrowserCallback,
    CodeExchange,
    IdentityVerification,
    SavingConnection,
}

/** Why ChatGPT sign-in failed. */
public enum class SignInFailure {
    /** The device could not open or persist the local credential. */
    StorageUnavailable,
    Cancelled,
    Rejected,
    NetworkError,
}

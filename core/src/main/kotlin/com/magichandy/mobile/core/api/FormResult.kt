package com.magichandy.mobile.core.api

/**
 * Only configuration forms implement this interface. Direct Stop and ordinary controls have
 * separate entry points.
 */
public sealed interface FormIntent : UserIntent

/** Completion of one submitted form, including the durable write when it succeeds. */
public sealed interface FormResult {
    public data object Accepted : FormResult

    public data class Rejected(public val reason: FormReason) : FormResult
}

/** Closed, localizable reasons. The result never contains user input or peer error text. */
public enum class FormReason {
    InvalidKey,
    InvalidAddress,
    InvalidName,
    InvalidModel,
    Unsupported,
    ConsentRequired,
    Unreachable,
    ProviderRefused,
    /** The discovered model did not finish the structured reply test required for chat. */
    IncompleteModelReply,
    Duplicate,
    ChooseDevice,
    /** Two features have the same durable description. They need distinct names on the server. */
    AmbiguousDevice,
    NotFound,
    RunInProgress,
    ReconcileDevice,
    Busy,
    StorageUnavailable,
    /** A text is longer than its limit, such as a persona description. */
    TooLong,
    /** There are already as many as the app keeps, such as personas. */
    LimitReached,
}

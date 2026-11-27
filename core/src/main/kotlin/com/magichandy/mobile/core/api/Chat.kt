package com.magichandy.mobile.core.api

import kotlin.time.Instant

/**
 * The open chat and the chat list.
 *
 * @property chatId null until the first chat exists.
 * @property title the chat's name, such as "Evening".
 * @property personaName the persona speaking in this chat.
 * @property modelName the chat model's short name, such as "Terra"; null when none is set up.
 * @property entries the transcript, oldest first.
 * @property chats every saved chat, most recent first, for the chat list.
 * @property sendBlockedBy why a message can't be sent now; null when it can.
 */
public data class ChatState(
    public val chatId: ChatId?,
    public val title: String,
    public val personaName: String,
    public val modelName: String?,
    public val entries: List<ChatEntry>,
    public val reply: ReplyState,
    public val chats: List<ChatSummary>,
    public val sendBlockedBy: SendBlock?,
    /** Earlier entries are on disk and can be requested with [UserIntent.LoadOlderMessages]. */
    public val hasOlderMessages: Boolean = false,
)

/** The largest ordinary chat message the core can accept, in UTF-16 code units. */
public const val maximumMessageLength: Int = 16000

/** True when an ordinary turn can be submitted or the text is a direct Stop. */
public fun ChatState.canSendMessage(text: String): Boolean =
    text.length <= maximumMessageLength &&
        text.isNotBlank() &&
        (isChatStopMessage(text) || sendBlockedBy == null && reply !is ReplyState.Streaming)

/** Why the composer can't send. */
public enum class SendBlock {
    NoChatModel,
    ModelUnreachable,
}

/** One line of the transcript. */
@kotlinx.serialization.Serializable
public sealed interface ChatEntry {
    public val id: EntryId
    public val at: Instant

    /** A message from you or the persona, with what it commanded, if anything. */
    @kotlinx.serialization.Serializable
    public data class Message(
        override val id: EntryId,
        override val at: Instant,
        public val author: Author,
        public val text: String,
        public val receipt: CommandReceipt?,
        /** The persona's name when it wrote this; null for your messages and older entries. */
        public val speaker: String? = null,
    ) : ChatEntry

    /** Motion stopped during this chat. [lostDevice] names the device whose loss caused it. */
    @kotlinx.serialization.Serializable
    public data class MotionStopped(
        override val id: EntryId,
        override val at: Instant,
        public val lostDevice: String?,
    ) : ChatEntry
}

/** Who wrote a message. */
public enum class Author {
    You,
    Persona,
}

/** What a reply commanded, shown under it: "Pace 62 → 48 · Stroke 15–85 · commanded". */
@kotlinx.serialization.Serializable
public data class CommandReceipt(
    public val changes: List<ValueChange>,
    /** Stroke geometry accepted for this turn, present when the pattern changed. */
    public val pattern: StrokePatternReceipt? = null,
)

/** The requested stroke pattern within the saved stroke limits, in mounting-neutral coordinates. */
@kotlinx.serialization.Serializable
public data class StrokePatternReceipt(
    public val low: Int,
    public val high: Int,
    /** An ordered repeating phrase. An empty list reports only the enclosing stroke range. */
    public val groups: List<StrokeGroupReceipt> = emptyList(),
)

/** Number of strokes between [low] and [high] in this part of the phrase. */
@kotlinx.serialization.Serializable
public data class StrokeGroupReceipt(
    public val count: Int,
    public val low: Int,
    public val high: Int,
)

/** One commanded value. [from] is null when the control had no earlier value in this run. */
@kotlinx.serialization.Serializable
public data class ValueChange(
    public val control: ControlKind,
    public val from: Int?,
    public val to: Int,
)

/** A value the app commands. The UI shows stroke as a range when both ends change together. */
public enum class ControlKind {
    Pace,
    StrokeLow,
    StrokeHigh,
    Level,
    Tempo,
}

/** The state of the persona's reply. */
public sealed interface ReplyState {
    public data object Idle : ReplyState

    /**
     * The text so far. Nothing is saved or acted on until the reply completes and validates.
     *
     * @property speaker the persona name captured when this turn started, or null when omitted.
     */
    public data class Streaming(public val text: String, public val speaker: String? = null) :
        ReplyState

    /** The reply was discarded. Your message stays, and [UserIntent.SendAgain] resends it. */
    public data class Interrupted(
        public val cause: InterruptCause,
        /** Provider-specific recovery information when supplied by the selected connection. */
        public val providerFailure: ProviderReplyFailure? = null,
    ) : ReplyState
}

/** Why a reply was discarded. */
public enum class InterruptCause {
    MotionStopped,
    ConnectionLost,
    AppInBackground,
    ProviderError,
    /**
     * The provider explicitly declined this turn. The user may choose an alternative and resend.
     */
    ProviderRefused,
}

/** A saved chat in the chat list. */
@kotlinx.serialization.Serializable
public data class ChatSummary(
    public val id: ChatId,
    public val title: String,
    public val personaName: String,
    public val lastActivity: Instant,
)

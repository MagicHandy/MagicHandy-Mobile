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
)

/** Why the composer can't send. */
public enum class SendBlock {
    NoChatModel,
    ModelUnreachable,
}

/** One line of the transcript. */
public sealed interface ChatEntry {
    public val id: EntryId
    public val at: Instant

    /** A message from you or the persona, with what it commanded, if anything. */
    public data class Message(
        override val id: EntryId,
        override val at: Instant,
        public val author: Author,
        public val text: String,
        public val receipt: CommandReceipt?,
    ) : ChatEntry

    /** Motion stopped during this chat. [lostDevice] names the device whose loss caused it. */
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
public data class CommandReceipt(public val changes: List<ValueChange>)

/** One commanded value. [from] is null when the control had no earlier value in this run. */
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

    /** The text so far. Nothing is saved or acted on until the reply completes and validates. */
    public data class Streaming(public val text: String) : ReplyState

    /** The reply was discarded. Your message stays, and [UserIntent.SendAgain] resends it. */
    public data class Interrupted(public val cause: InterruptCause) : ReplyState
}

/** Why a reply was discarded. */
public enum class InterruptCause {
    MotionStopped,
    ConnectionLost,
    AppInBackground,
    ProviderError,
}

/** A saved chat in the chat list. */
public data class ChatSummary(
    public val id: ChatId,
    public val title: String,
    public val personaName: String,
    public val lastActivity: Instant,
)

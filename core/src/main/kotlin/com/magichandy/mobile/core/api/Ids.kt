package com.magichandy.mobile.core.api

/** Identifies a saved chat. */
@kotlinx.serialization.Serializable @JvmInline public value class ChatId(public val value: String)

/** Identifies one entry in a chat transcript. */
@kotlinx.serialization.Serializable @JvmInline public value class EntryId(public val value: String)

/** Identifies a saved device. Stable across reconnections. */
@kotlinx.serialization.Serializable @JvmInline public value class DeviceId(public val value: String)

/** Identifies a saved model connection. */
@kotlinx.serialization.Serializable
@JvmInline
public value class ConnectionId(public val value: String)

/** Identifies a saved persona. The empty ID belongs to the built-in MagicHandy. */
@kotlinx.serialization.Serializable
@JvmInline
public value class PersonaId(public val value: String)

/** Identifies one result of a Bluetooth scan. Valid until the scan ends. */
@JvmInline public value class ScanResultId(public val value: String)

/** A whole-number percentage from 0 to 100. */
@JvmInline
@kotlinx.serialization.Serializable
public value class Percent(public val value: Int) {
    init {
        require(value in 0..100) { "A percentage must be 0..100, was $value" }
    }
}

/**
 * A secret, such as an API key or a connection key. Its [toString] is redacted, so it can't leak
 * through logs or a data class's generated text (plan §5.5).
 */
@JvmInline
public value class Secret(private val value: String) {
    /** The secret itself. Pass it straight to the store or transport that needs it. */
    public fun reveal(): String = value

    override fun toString(): String = "Secret(redacted)"
}

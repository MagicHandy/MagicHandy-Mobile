package com.magichandy.mobile.core.chat

/**
 * Count JVM UTF-8 content without copying potentially long stored messages just to measure them.
 */
internal fun String.utf8Length(): Long {
    var bytes = 0L
    var i = 0
    while (i < length) {
        val character = this[i++]
        bytes +=
            when {
                character.code < 0x80 -> 1
                character.code < 0x800 -> 2
                character.isHighSurrogate() && i < length && this[i].isLowSurrogate() -> {
                    i++
                    4
                }
                character.isSurrogate() ->
                    1 // The JVM UTF-8 encoder writes '?' for an unpaired surrogate.
                else -> 3
            }
    }
    return bytes
}

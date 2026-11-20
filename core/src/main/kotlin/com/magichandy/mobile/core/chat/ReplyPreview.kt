package com.magichandy.mobile.core.chat

/**
 * Incrementally display only the reply string. A provisional character has no execution authority.
 */
internal class ReplyPreview {
    private val token = StringBuilder()
    private var quoted = false
    private var slash = false
    private var key = false
    private var value = false
    private var speaking = false
    private var unicode = 0
    private val escape = StringBuilder()

    fun consume(delta: String): String = buildString {
        for (ch in delta) {
            if (!quoted) {
                when {
                    ch == '"' -> {
                        quoted = true
                        speaking = value
                        value = false
                        key = false
                        token.setLength(0)
                    }
                    ch == ':' && key -> {
                        value = true
                        key = false
                    }
                    !ch.isWhitespace() -> {
                        key = false
                        value = false
                    }
                }
                continue
            }
            if (unicode > 0) {
                escape.append(ch)
                unicode--
                if (unicode == 0 && speaking)
                    escape.toString().toIntOrNull(16)?.let { append(it.toChar()) }
                continue
            }
            if (slash) {
                slash = false
                if (ch == 'u') {
                    unicode = 4
                    escape.setLength(0)
                } else if (speaking) {
                    append(
                        when (ch) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'b' -> '\b'
                            else -> ch
                        }
                    )
                }
                continue
            }
            when (ch) {
                '\\' -> slash = true
                '"' -> {
                    quoted = false
                    key = !speaking && token.toString() == "reply"
                    speaking = false
                }
                else -> if (speaking) append(ch) else if (token.length < 6) token.append(ch)
            }
        }
    }
}

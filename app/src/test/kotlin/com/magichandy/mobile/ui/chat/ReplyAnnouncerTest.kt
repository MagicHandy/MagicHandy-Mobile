package com.magichandy.mobile.ui.chat

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** TalkBack hears a streaming reply in whole sentences, never a sentence still being written. */
internal class ReplyAnnouncerTest {
    @Test
    fun `a sentence is read once the next one has begun`() {
        val text = "Slowing down now. Breathe with me"
        assertEquals("Slowing down now. ".length, completedSentencesEnd(text, 0, Locale.ENGLISH))
    }

    @Test
    fun `the last sentence waits, since the stream may add to it`() {
        assertNull(completedSentencesEnd("Slowing down now.", 0, Locale.ENGLISH))
        assertNull(completedSentencesEnd("Slowing down", 0, Locale.ENGLISH))
    }

    @Test
    fun `sentences already read are not read again`() {
        val text = "One. Two. Three"
        val read = completedSentencesEnd(text, 0, Locale.ENGLISH)
        assertEquals("One. Two. ".length, read)
        assertNull(completedSentencesEnd(text, read!!, Locale.ENGLISH))
        val more = completedSentencesEnd("$text. Four", read, Locale.ENGLISH)
        assertEquals("One. Two. Three. ".length, more)
    }

    @Test
    fun `Japanese and Chinese sentences end at their own full stops`() {
        val japanese = completedSentencesEnd("ゆっくりね。もっと", 0, Locale.JAPANESE)
        assertEquals("ゆっくりね。".length, japanese)
        val chinese = completedSentencesEnd("慢一点。再来", 0, Locale.SIMPLIFIED_CHINESE)
        assertEquals("慢一点。".length, chinese)
    }
}

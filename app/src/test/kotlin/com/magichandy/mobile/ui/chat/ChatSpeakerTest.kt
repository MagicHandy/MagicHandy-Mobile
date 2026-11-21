package com.magichandy.mobile.ui.chat

import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.EntryId
import com.magichandy.mobile.core.api.ReplyState
import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Transcript speaker labels stay fixed to the persona that spoke, including pre-persona rows. */
internal class ChatSpeakerTest {
    private val at = Instant.fromEpochSeconds(1)

    @Test
    fun `historical pre-persona replies keep the built-in name after switching personas`() {
        val historical = ChatEntry.Message(EntryId("old"), at, Author.Persona, "Earlier.", null)
        val blankSpeaker = historical.copy(id = EntryId("blank"), speaker = "   ")
        val custom = ChatEntry.Message(EntryId("kit"), at, Author.Persona, "Later.", null, "Kit")
        assertEquals("MagicHandy", messageSpeaker(historical, "You", "MagicHandy"))
        assertEquals("MagicHandy", messageSpeaker(blankSpeaker, "You", "MagicHandy"))
        assertEquals("Kit", messageSpeaker(custom, "You", "MagicHandy"))
    }

    @Test
    fun `live streaming preview keeps its captured speaker when the active persona changes`() {
        val streaming = ReplyState.Streaming("Still replying", speaker = "Kit")
        assertEquals("Kit", streamingSpeaker(streaming, activePersonaName = "MagicHandy"))
        assertEquals("Kit", streamingSpeaker(streaming, activePersonaName = "Kat"))
        assertEquals(
            "MagicHandy",
            streamingSpeaker(ReplyState.Streaming("Legacy"), activePersonaName = "MagicHandy"),
        )
    }

    @Test
    fun `user messages keep the You label regardless of speaker metadata`() {
        val own = ChatEntry.Message(EntryId("u1"), at, Author.You, "Hello", null, speaker = "Kit")
        assertEquals("You", messageSpeaker(own, "You", "MagicHandy"))
    }
}

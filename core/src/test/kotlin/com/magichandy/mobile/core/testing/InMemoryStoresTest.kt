package com.magichandy.mobile.core.testing

import com.magichandy.mobile.core.api.AppLanguage
import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.ChatSummary
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.EntryId
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.PreferenceField
import com.magichandy.mobile.core.ports.SavedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

internal class InMemoryStoresTest {
    @Test
    fun `removing a saved connection clears only its bound roles`() = runTest {
        val store = InMemoryModels()
        val first =
            ModelConnection(
                ConnectionId("one"),
                "One",
                ProviderKind.ChatGpt,
                "one",
                "one",
                CapabilityLevel.Advanced,
                true,
                true,
                ConnectionStatus.Ready,
            )
        val second = first.copy(id = ConnectionId("two"))
        store.save(SavedModel(first))
        store.save(SavedModel(second))
        store.bindPlanner(second.id)
        store.bindChat(ConnectionId("missing"))
        assertEquals(first.id, store.state.value.chat)
        store.remove(second.id)
        assertEquals(null, store.state.value.planner)
        assertEquals(setOf(first.id), store.state.value.models.keys)
        store.remove(first.id)
        assertEquals(null, store.state.value.chat)
    }

    @Test
    fun `the diagnostic ring retains only the newest redacted event kinds`() = runTest {
        val ring = InMemoryDiagnostics(2)
        for (i in 1..3) ring.record(
            Instant.fromEpochSeconds(i.toLong()),
            DiagnosticKind.StopRequested,
        )
        assertEquals(listOf(2L, 3L), ring.events.value.map { it.first.epochSeconds })
    }

    @Test
    fun `stale disjoint fields merge and a conflicting patch is rejected as a whole`() = runTest {
        val store = InMemorySettings()
        assertTrue(store.patch(0, listOf(PreferenceField.PaletteChoice.edit(Palette.Lowlight))))
        assertTrue(store.patch(0, listOf(PreferenceField.Language.edit(AppLanguage.Japanese))))
        assertFalse(
            store.patch(
                0,
                listOf(
                    PreferenceField.PaletteChoice.edit(Palette.HighContrast),
                    PreferenceField.Memory.edit(true),
                ),
            )
        )
        val result = store.state.value
        assertEquals(2L, result.revision)
        assertEquals(Palette.OledBlack, result.values.palette)
        assertEquals(AppLanguage.Japanese, result.values.language)
        assertFalse(result.values.memoryOn)
        assertFalse(store.patch(3, listOf(PreferenceField.Memory.edit(true))))
    }

    @Test
    fun `turn and memory are committed once together or discarded together after a fence`() =
        runTest {
            val store = InMemoryChats()
            val chat = ChatId("chat")
            val persona = PersonaId("persona")
            val at = Instant.fromEpochSeconds(0)
            store.create(ChatSummary(chat, "", "", at))
            val reply = ChatEntry.Message(EntryId("reply"), at, Author.Persona, "Hello", null)
            assertFalse(store.append(chat, reply, persona, listOf("synthetic")) { false })
            assertTrue(store.state.value.memory.isEmpty())
            assertTrue(store.state.value.chats.getValue(chat).messages.isEmpty())
            repeat(2) {
                assertTrue(store.append(chat, reply, persona, listOf("synthetic")) { true })
            }
            assertEquals(listOf(reply), store.state.value.chats.getValue(chat).messages)
            assertEquals(listOf("synthetic"), store.state.value.memory[persona])
        }
}

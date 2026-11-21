package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.ChatSummary
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.EntryId
import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.PersonaLimits
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.api.characterCount
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.PersonaChange
import com.magichandy.mobile.core.ports.SettingsDocument
import com.magichandy.mobile.core.ports.SettingsStore
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.builtInPersona
import com.magichandy.mobile.core.testing.InMemoryChats
import com.magichandy.mobile.core.testing.InMemorySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class PersonaLifecycleTest {
    @Test
    fun `a blocked persona save cannot delay direct Stop or restart the run`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val f =
            DebugFixture(this) { d ->
                d.copy(
                    settings =
                        object : SettingsStore by d.settings {
                            override suspend fun editPersonas(
                                edit: (SettingsDocument) -> PersonaChange
                            ): FormResult {
                                waiting.complete(Unit)
                                finish.await()
                                return d.settings.editPersonas(edit)
                            }
                        }
                )
            }
        f.onboard()
        f.connect(DeviceId("handy"))
        f.send(UserIntent.StartMotion)
        assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
        val save = async {
            f.core.submit(
                UserIntent.SavePersona(
                    null,
                    "Synthetic",
                    Personality(description = "start motion"),
                    true,
                )
            )
        }
        waiting.await()
        f.core.emergencyStop(StopSource.AppFooter)
        runCurrent()
        assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
        assertFalse(save.isCompleted)
        finish.complete(Unit)
        assertEquals(FormResult.Accepted, save.await())
        advanceTimeBy(1000)
        runCurrent()
        assertNull(f.d.journal.read())
        assertEquals(0, f.devices.output.value.getValue(DeviceId("handy")).speed.value)
        f.scope.cancel()
    }

    @Test
    fun `saving the built-in defaults preserves selection and memories and repeating a save leaves its revision alone`() =
        runTest {
            val f = DebugFixture(this)
            f.onboard()
            val core = f.core
            f.send(UserIntent.SetMemory(true))
            f.send(UserIntent.SendMessage("Remember that fixture."))
            advanceTimeBy(150)
            runCurrent()
            core.submit(
                UserIntent.SavePersona(
                    builtInPersona.id,
                    "MagicHandy",
                    Personality(Explicitness.Intimate),
                    false,
                )
            )
            core.submit(UserIntent.SavePersona(null, "Other", Personality(), true))
            val other = core.state.value.settings.persona
            val reset = UserIntent.SavePersona(builtInPersona.id, "MagicHandy", Personality())
            assertEquals(FormResult.Accepted, core.submit(reset))
            assertEquals(other, core.state.value.settings.persona)
            val revision = f.d.settings.state.value.revision
            core.submit(reset)
            f.send(UserIntent.SetPersona(other.id))
            assertEquals(revision, f.d.settings.state.value.revision)
            f.send(UserIntent.SetPersona(builtInPersona.id))
            assertEquals(builtInPersona, core.state.value.settings.persona)
            assertEquals(listOf("fixture."), core.state.value.settings.memories)
            f.scope.cancel()
        }

    @Test
    fun `debug replies show their captured name and level with the same motion and Stop behavior`() =
        runTest {
            val f = DebugFixture(this)
            f.onboard()
            f.connect(DeviceId("handy"))
            f.core.submit(
                UserIntent.SavePersona(null, "Kit", Personality(Explicitness.Explicit), use = true)
            )
            fun lastReply() =
                f.core.state.value.chat.entries.filterIsInstance<ChatEntry.Message>().last {
                    it.author == Author.Persona
                }
            f.send(UserIntent.SendMessage("Start motion"))
            f.send(UserIntent.SetPersona(builtInPersona.id))
            advanceTimeBy(300)
            runCurrent()
            assertTrue(lastReply().text.startsWith("(Kit · Explicit) "))
            assertEquals("Kit", lastReply().speaker)
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            val journal = f.d.journal.read()?.runId
            f.send(UserIntent.SendMessage("Hello"))
            advanceTimeBy(150)
            runCurrent()
            assertTrue(lastReply().text.startsWith("(MagicHandy · Utility) "))
            assertEquals(journal, f.d.journal.read()?.runId)
            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            assertEquals(0, f.devices.output.value.getValue(DeviceId("handy")).speed.value)
            f.scope.cancel()
        }

    @Test
    fun `fresh setup survives acknowledgement until FinishSetup and survives deleting all chats`() =
        runTest {
            val f = DebugFixture(this)
            assertFalse(f.core.state.value.firstRun.isSetupDone)
            f.send(UserIntent.FinishSetup)
            assertFalse(f.core.state.value.firstRun.isSetupDone)
            f.onboard()
            assertFalse(f.core.state.value.firstRun.isSetupDone)
            f.d.models.remove(requireNotNull(f.d.models.state.value.chat))
            f.send(UserIntent.FinishSetup)
            assertTrue(f.core.state.value.firstRun.isSetupDone)
            val revision = f.d.settings.state.value.revision
            f.send(UserIntent.FinishSetup)
            assertEquals(revision, f.d.settings.state.value.revision)
            f.send(UserIntent.DeleteAllChatsAndMemory)
            assertTrue(f.core.state.value.firstRun.isSetupDone)
            assertTrue(f.core.state.value.models.connections.isEmpty())
            assertNull(f.d.journal.read())
            f.scope.cancel()
        }

    @Test
    fun `forms share UI character counts and never echo invalid input`() = runTest {
        val f = DebugFixture(this)
        val core = f.core
        val grapheme = "é"
        assertEquals(1, characterCount(grapheme))
        val voice = Personality(description = grapheme.repeat(PersonaLimits.DESCRIPTION))
        val name = grapheme.repeat(PersonaLimits.NAME)
        suspend fun save(n: String, p: Personality = voice, id: PersonaId? = null) =
            core.submit(UserIntent.SavePersona(id, n, p))
        assertEquals(FormResult.Accepted, save("  $name  "))
        assertEquals(name, core.state.value.settings.personas.last().name)
        assertEquals(FormResult.Rejected(FormReason.InvalidName), save("   "))
        assertEquals(FormResult.Rejected(FormReason.InvalidName), save(name + grapheme))
        assertEquals(
            FormResult.Rejected(FormReason.TooLong),
            save("Ok", voice.copy(description = voice.description + grapheme)),
        )
        assertEquals(FormResult.Rejected(FormReason.InvalidName), save("MAGICHANDY"))
        assertEquals(
            FormResult.Rejected(FormReason.InvalidName),
            save("Changed", id = builtInPersona.id),
        )
        assertEquals(
            FormResult.Rejected(FormReason.NotFound),
            save("Unknown", id = PersonaId("absent")),
        )
        assertEquals(FormResult.Accepted, save("Zed"))
        assertEquals(FormResult.Rejected(FormReason.Duplicate), save("zeD"))
        assertEquals(
            FormResult.Rejected(FormReason.Unsupported),
            core.submit(UserIntent.DeletePersona(builtInPersona.id)),
        )
        assertEquals(
            FormResult.Rejected(FormReason.NotFound),
            core.submit(UserIntent.DuplicatePersona(PersonaId("absent"), "Copy")),
        )
        assertEquals(
            FormResult.Rejected(FormReason.NotFound),
            core.submit(UserIntent.DeletePersona(PersonaId("absent"))),
        )
        f.scope.cancel()
    }

    @Test
    fun `capacity counts only custom personas and permits editing and deletion when full`() =
        runTest {
            val f = DebugFixture(this)
            val core = f.core
            repeat(PersonaLimits.PERSONAS) {
                assertEquals(
                    FormResult.Accepted,
                    core.submit(UserIntent.SavePersona(null, "Profile $it", Personality())),
                )
            }
            val settings = core.state.value.settings
            val names = settings.personas.drop(1).map { it.name }
            assertEquals(names.sortedWith(String.CASE_INSENSITIVE_ORDER), names)
            assertTrue(settings.personas.all { it.summary.isEmpty() })
            assertTrue(settings.persona.isBuiltIn)
            val full = FormResult.Rejected(FormReason.LimitReached)
            assertEquals(full, core.submit(UserIntent.SavePersona(null, "Overflow", Personality())))
            assertEquals(
                full,
                core.submit(UserIntent.DuplicatePersona(settings.persona.id, "Copy")),
            )
            val first = settings.personas[1]
            assertEquals(
                FormResult.Accepted,
                core.submit(UserIntent.SavePersona(first.id, "Renamed", Personality())),
            )
            assertEquals(FormResult.Accepted, core.submit(UserIntent.DeletePersona(first.id)))
            assertEquals(
                FormResult.Accepted,
                core.submit(UserIntent.DuplicatePersona(settings.persona.id, "Copy")),
            )
            f.scope.cancel()
        }

    @Test
    fun `switch rename duplicate and delete preserve historical speaker and isolate late memory`() =
        runTest {
            val f = DebugFixture(this)
            f.onboard()
            val core = f.core
            val voice = Personality(Explicitness.Warm, description = "Synthetic description")
            assertFalse(core.state.value.settings.isMemoryOn)
            f.send(UserIntent.SetMemory(true))
            assertEquals(
                FormResult.Accepted,
                core.submit(UserIntent.SavePersona(null, "Kit", voice, use = true)),
            )
            val kit = core.state.value.settings.persona
            assertEquals("Kit", core.state.value.chat.personaName)
            f.send(UserIntent.SendMessage("Remember that one."))
            f.send(UserIntent.SetPersona(builtInPersona.id))
            advanceTimeBy(150)
            runCurrent()
            assertTrue(core.state.value.settings.memories.isEmpty())
            f.send(UserIntent.SetPersona(kit.id))
            assertEquals(listOf("one."), core.state.value.settings.memories)
            assertEquals(
                FormResult.Accepted,
                core.submit(UserIntent.DuplicatePersona(kit.id, "Alpha")),
            )
            val copy = core.state.value.settings.personas[1]
            assertEquals(voice, copy.personality)
            assertEquals(kit, core.state.value.settings.persona)
            f.send(UserIntent.SetPersona(copy.id))
            assertTrue(core.state.value.settings.memories.isEmpty())
            f.send(UserIntent.SendMessage("Remember that two."))
            advanceTimeBy(150)
            runCurrent()
            f.send(UserIntent.ClearMemory)
            assertTrue(core.state.value.settings.memories.isEmpty())
            f.send(UserIntent.SetPersona(kit.id))
            assertEquals(listOf("one."), core.state.value.settings.memories)
            f.send(UserIntent.SendMessage("Remember that three."))
            assertEquals(
                FormResult.Accepted,
                core.submit(UserIntent.SavePersona(kit.id, "New name", voice)),
            )
            assertEquals(FormResult.Accepted, core.submit(UserIntent.DeletePersona(kit.id)))
            advanceTimeBy(150)
            runCurrent()
            assertTrue(core.state.value.settings.persona.isBuiltIn)
            assertFalse(kit.id in f.d.chats.state.value.memory)
            f.send(UserIntent.SendMessage("Last."))
            advanceTimeBy(150)
            runCurrent()
            val messages = core.state.value.chat.entries.filterIsInstance<ChatEntry.Message>()
            assertEquals(
                listOf("Kit", "Alpha", "Kit", "MagicHandy"),
                messages.filter { it.author == Author.Persona }.map { it.speaker },
            )
            messages.filter { it.author == Author.You }.forEach { assertNull(it.speaker) }
            f.scope.cancel()
        }

    @Test
    fun `historical pre-persona replies keep MagicHandy across startup open and persona switch`() =
        runTest {
            val chats = InMemoryChats()
            val chatId = ChatId("legacy")
            val t0 = Instant.fromEpochSeconds(10)
            val t1 = Instant.fromEpochSeconds(20)
            chats.create(ChatSummary(chatId, "Earlier", builtInPersona.name, t0))
            chats.append(
                chatId,
                ChatEntry.Message(EntryId("u1"), t0, Author.You, "Earlier question", null),
                builtInPersona.id,
                emptyList(),
            ) {
                true
            }
            chats.append(
                chatId,
                ChatEntry.Message(EntryId("a1"), t1, Author.Persona, "Earlier reply", null),
                builtInPersona.id,
                emptyList(),
            ) {
                true
            }
            val f =
                DebugFixture(this) { d ->
                    d.copy(chats = chats, settings = InMemorySettings(chats = chats))
                }
            fun speakers() =
                f.core.state.value.chat.entries.filterIsInstance<ChatEntry.Message>().map {
                    it.speaker
                }
            assertEquals(listOf(null, "MagicHandy"), speakers())
            f.onboard()
            assertEquals(
                FormResult.Accepted,
                f.core.submit(
                    UserIntent.SavePersona(null, "Kit", Personality(Explicitness.Warm), use = true)
                ),
            )
            assertEquals("Kit", f.core.state.value.chat.personaName)
            assertEquals(listOf(null, "MagicHandy"), speakers())
            f.send(UserIntent.OpenChat(chatId))
            assertEquals(listOf(null, "MagicHandy"), speakers())
            f.send(UserIntent.SendMessage("Hello"))
            advanceTimeBy(150)
            runCurrent()
            assertEquals(listOf(null, "MagicHandy", null, "Kit"), speakers())
            f.send(UserIntent.SetPersona(builtInPersona.id))
            assertEquals(listOf(null, "MagicHandy", null, "Kit"), speakers())
            f.scope.cancel()
        }

    @Test
    fun `live streaming preview keeps its captured speaker across mid-turn switch rename and delete`() =
        runTest {
            val secondChunkGate = CompletableDeferred<Unit>()
            val finishGate = CompletableDeferred<Unit>()
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        provider = {
                            object : StructuredProvider {
                                override suspend fun complete(
                                    request: ModelRequest,
                                    preview: (String) -> Unit,
                                ): TextResult {
                                    preview("""{"reply":"Still speaking""")
                                    secondChunkGate.await()
                                    preview(""" more."}""")
                                    finishGate.await()
                                    return TextResult.Completed(
                                        """{"reply":"Still speaking more."}"""
                                    )
                                }

                                override suspend fun stream(
                                    messages: List<TextMessage>,
                                    preview: (String) -> Unit,
                                ): TextResult = error("unexpected")
                            }
                        }
                    )
                }
            f.onboard()
            val voice = Personality(Explicitness.Warm)
            assertEquals(
                FormResult.Accepted,
                f.core.submit(UserIntent.SavePersona(null, "Kit", voice, use = true)),
            )
            val kit = f.core.state.value.settings.persona
            f.send(UserIntent.SendMessage("Hello"))
            assertEquals(
                ReplyState.Streaming("Still speaking", "Kit"),
                f.core.state.value.chat.reply,
            )

            f.send(UserIntent.SetPersona(builtInPersona.id))
            assertEquals("MagicHandy", f.core.state.value.chat.personaName)
            assertEquals(
                ReplyState.Streaming("Still speaking", "Kit"),
                f.core.state.value.chat.reply,
            )

            assertEquals(
                FormResult.Accepted,
                f.core.submit(UserIntent.SavePersona(kit.id, "Kat", voice, use = true)),
            )
            assertEquals("Kat", f.core.state.value.chat.personaName)
            assertEquals(
                ReplyState.Streaming("Still speaking", "Kit"),
                f.core.state.value.chat.reply,
            )

            assertEquals(FormResult.Accepted, f.core.submit(UserIntent.DeletePersona(kit.id)))
            assertEquals("MagicHandy", f.core.state.value.chat.personaName)
            assertEquals(
                ReplyState.Streaming("Still speaking", "Kit"),
                f.core.state.value.chat.reply,
            )

            advanceTimeBy(40)
            secondChunkGate.complete(Unit)
            runCurrent()
            assertEquals(
                ReplyState.Streaming("Still speaking more.", "Kit"),
                f.core.state.value.chat.reply,
            )

            finishGate.complete(Unit)
            runCurrent()
            val committed = assertIs<ChatEntry.Message>(f.core.state.value.chat.entries.last())
            assertEquals("Still speaking more.", committed.text)
            assertEquals("Kit", committed.speaker)
            f.scope.cancel()
        }
}

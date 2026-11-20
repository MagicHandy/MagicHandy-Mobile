package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.InterruptCause
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.ChatStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class ChatCommitTest {
    @Test
    fun `Stop after the reply commits cannot interrupt it or offer a duplicate retry`() = runTest {
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f =
            DebugFixture(this) { d ->
                d.copy(
                    chats =
                        object : ChatStore by d.chats {
                            override suspend fun append(
                                chat: ChatId,
                                entry: ChatEntry,
                                persona: PersonaId,
                                memories: List<String>,
                                admitted: () -> Boolean,
                            ): Boolean {
                                val saved = d.chats.append(chat, entry, persona, memories, admitted)
                                if (
                                    saved &&
                                        entry is ChatEntry.Message &&
                                        entry.author == Author.Persona
                                ) {
                                    committed.complete(Unit)
                                    release.await()
                                }
                                return saved
                            }
                        }
                )
            }
        f.onboard()
        f.send(UserIntent.SendMessage("Hello"))
        advanceTimeBy(110)
        runCurrent()
        assertTrue(committed.isCompleted)
        f.core.emergencyStop(StopSource.AppFooter)
        runCurrent()
        assertEquals(ReplyState.Idle, f.core.state.value.chat.reply)
        assertFalse(requireNotNull(f.core.state.value.stopReceipt).didCancelReply)
        release.complete(Unit)
        runCurrent()
        assertEquals(ReplyState.Idle, f.core.state.value.chat.reply)
        f.send(UserIntent.SendAgain)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(
            listOf(Author.You, Author.Persona),
            f.core.state.value.chat.entries.filterIsInstance<ChatEntry.Message>().map { it.author },
        )
        f.scope.cancel()
    }

    @Test
    fun `Stop before the store admits a reply leaves only the user message committed`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f =
            DebugFixture(this) { d ->
                d.copy(
                    chats =
                        object : ChatStore by d.chats {
                            override suspend fun append(
                                chat: ChatId,
                                entry: ChatEntry,
                                persona: PersonaId,
                                memories: List<String>,
                                admitted: () -> Boolean,
                            ): Boolean {
                                if (entry is ChatEntry.Message && entry.author == Author.Persona) {
                                    waiting.complete(Unit)
                                    release.await()
                                }
                                return d.chats.append(chat, entry, persona, memories, admitted)
                            }
                        }
                )
            }
        f.onboard()
        f.send(UserIntent.SendMessage("Hello"))
        advanceTimeBy(110)
        runCurrent()
        assertTrue(waiting.isCompleted)
        f.core.emergencyStop(StopSource.Notification)
        runCurrent()
        assertTrue(requireNotNull(f.core.state.value.stopReceipt).didCancelReply)
        release.complete(Unit)
        runCurrent()
        assertEquals(
            ReplyState.Interrupted(InterruptCause.MotionStopped),
            f.core.state.value.chat.reply,
        )
        assertEquals(
            listOf(Author.You),
            f.core.state.value.chat.entries.filterIsInstance<ChatEntry.Message>().map { it.author },
        )
        f.scope.cancel()
    }

    @Test
    fun `typed Stop in another locale interrupts a canned SSE preview and Send again keeps one user message`() =
        runTest {
            val f = DebugFixture(this)
            f.onboard()
            f.send(UserIntent.SendMessage("Hello"))
            advanceTimeBy(51)
            runCurrent()
            assertTrue((f.core.state.value.chat.reply as ReplyState.Streaming).text.isNotEmpty())
            f.send(UserIntent.SendMessage("¡Detén el movimiento!"))
            assertEquals(
                ReplyState.Interrupted(InterruptCause.MotionStopped),
                f.core.state.value.chat.reply,
            )
            assertTrue(assertNotNull(f.core.state.value.stopReceipt).didCancelReply)
            advanceTimeBy(200)
            runCurrent()
            assertEquals(1, f.core.state.value.chat.entries.size)
            f.send(UserIntent.SendAgain)
            advanceTimeBy(110)
            runCurrent()
            assertEquals(
                listOf(Author.You, Author.Persona),
                f.core.state.value.chat.entries.filterIsInstance<ChatEntry.Message>().map {
                    it.author
                },
            )
            f.scope.cancel()
        }
}

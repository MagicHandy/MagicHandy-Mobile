package com.magichandy.mobile.voice

import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Stop checks go ahead of chat turns waiting for the recognizer (docs/voice-design.md §6). */
internal class RecognizerQueueTest {
    @Test
    fun `Stop checks go ahead of turns`() {
        val queue = RecognizerQueue()
        val turn = utterance()
        val short = FloatArray(1)
        val tail = FloatArray(2)
        queue.addTurn(turn)
        queue.checkShort(short)
        queue.checkTail(tail)
        assertSame(tail, (queue.take(0) as RecognizerJob.StopCheck).samples)
        assertSame(short, (queue.take(0) as RecognizerJob.StopCheck).samples)
        assertSame(turn, (queue.take(0) as RecognizerJob.Turn).utterance)
        assertNull(queue.take(0))
    }

    @Test
    fun `only the latest tail check is kept`() {
        val queue = RecognizerQueue()
        val older = FloatArray(1)
        val newer = FloatArray(2)
        queue.checkTail(older)
        queue.checkTail(newer)
        assertSame(newer, (queue.take(0) as RecognizerJob.StopCheck).samples)
        assertNull(queue.take(0))
    }

    @Test
    fun `turns keep the order they were heard in`() {
        val queue = RecognizerQueue()
        val first = utterance()
        val second = utterance()
        queue.addTurn(first)
        queue.addTurn(second)
        assertTrue(queue.hasTurns)
        assertSame(first, (queue.take(0) as RecognizerJob.Turn).utterance)
        assertSame(second, (queue.take(0) as RecognizerJob.Turn).utterance)
        assertFalse(queue.hasTurns)
    }

    @Test
    fun `closing drops what waits and wakes the recognizer`() {
        val queue = RecognizerQueue()
        queue.addTurn(utterance())
        queue.close()
        assertNull(queue.take(0))
        var woke: RecognizerJob? = RecognizerJob.StopCheck(FloatArray(0))
        val waiting = RecognizerQueue()
        val taker = thread { woke = waiting.take(10_000) }
        waiting.close()
        taker.join(5_000)
        assertFalse(taker.isAlive)
        assertNull(woke)
        // Nothing queues after closing.
        waiting.checkTail(FloatArray(1))
        assertNull(waiting.take(0))
    }

    @Test
    fun `a job that arrives while the recognizer waits wakes it`() {
        val queue = RecognizerQueue()
        val tail = FloatArray(3)
        var taken: RecognizerJob? = null
        val taker = thread { taken = queue.take(10_000) }
        queue.checkTail(tail)
        taker.join(5_000)
        assertEquals(tail, (taken as RecognizerJob.StopCheck).samples)
    }

    private fun utterance() = Utterance(0, 0, FloatArray(0), SpeechLevel(0f, 0f))
}

package com.magichandy.mobile.voice

import com.magichandy.mobile.core.api.SpeechLevel
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

    @Test
    fun `continuous Stop checks allow completed turns to progress and bound each decode`() {
        val queue = RecognizerQueue()
        val samples = FloatArray(SAMPLE_RATE * 20) { (it % 17).toFloat() }
        assertTrue(queue.addTurn(Utterance(0, samples.size.toLong(), samples, SpeechLevel(0f, 0f))))
        var copied = 0
        var checks = 0
        var completed = 0
        while (completed == 0) {
            queue.checkTail(floatArrayOf(1f))
            when (val next = requireNotNull(queue.take(0))) {
                is RecognizerJob.StopCheck -> {
                    checks++
                    assertTrue(checks <= 2)
                }
                is RecognizerJob.Turn -> {
                    checks = 0
                    val part = next.nextAudio()
                    assertTrue(part.size <= RecognizerJob.Turn.maximumSliceSamples)
                    assertTrue(samples.copyOfRange(copied, copied + part.size).contentEquals(part))
                    copied += part.size
                    if (next.finished) completed++ else queue.continueTurn(next)
                }
            }
        }
        assertEquals(samples.size, copied)
    }

    @Test
    fun `an ordinary sentence stays whole and a long turn yields inside a sustained pause`() {
        fun turn(audio: FloatArray) =
            RecognizerJob.Turn(Utterance(0, audio.size.toLong(), audio, SpeechLevel(0f, 0f)))
        val ordinary = FloatArray(SAMPLE_RATE * 5) { 0.25f }
        val sentence = turn(ordinary)
        assertTrue(ordinary.contentEquals(sentence.nextAudio()))
        assertTrue(sentence.finished)

        val pause = SAMPLE_RATE * 5 until SAMPLE_RATE * 5 + SAMPLE_RATE / 4
        val long = FloatArray(SAMPLE_RATE * 11) { if (it in pause) 0f else 0.25f }
        val passage = turn(long)
        val first = passage.nextAudio()
        assertTrue(first.size in pause)
        val second = passage.nextAudio()
        assertTrue(passage.finished)
        assertTrue(long.contentEquals(first + second))
    }

    @Test
    fun `backpressure bounds short sounds and retained turn samples`() {
        val queue = RecognizerQueue()
        repeat(8) { assertTrue(queue.checkShort(FloatArray(32))) }
        assertFalse(queue.checkShort(FloatArray(32)))
        val speech = Utterance(0, 320000, FloatArray(320000), SpeechLevel(0f, 0f))
        assertTrue(queue.addTurn(speech))
        assertTrue(queue.addTurn(speech))
        assertFalse(queue.addTurn(speech))
    }

    @Test
    fun `a Stop arriving during a long turn gets the next decode`() {
        val queue = RecognizerQueue()
        queue.addTurn(Utterance(0, 320000, FloatArray(320000), SpeechLevel(0f, 0f)))
        val running = queue.take(0) as RecognizerJob.Turn
        val firstSlice = running.nextAudio()
        queue.checkTail(floatArrayOf(1f))
        queue.continueTurn(running)
        assertFalse(running.finished)
        assertTrue(firstSlice.size <= RecognizerJob.Turn.maximumSliceSamples)
        assertTrue(queue.take(0) is RecognizerJob.StopCheck)
        assertSame(running, queue.take(0))
    }

    private fun utterance() = Utterance(0, 0, FloatArray(0), SpeechLevel(0f, 0f))
}

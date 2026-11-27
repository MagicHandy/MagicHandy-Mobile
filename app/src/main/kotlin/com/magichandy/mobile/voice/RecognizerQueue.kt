package com.magichandy.mobile.voice

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Something for the recognizer to hear. */
sealed interface RecognizerJob {
    /** Audio to check for a spoken Stop only. Its words never reach chat. */
    class StopCheck(val samples: FloatArray, val lastSpeechSample: Long = 0L) : RecognizerJob

    /** A turn of speech for chat, checked for Stop first. */
    class Turn(val utterance: Utterance) : RecognizerJob {
        private var offset = 0
        private val words = StringBuilder()
        val finished: Boolean
            get() = offset >= utterance.samples.size

        /**
         * Yield the recognizer between short sections. Prefer a quiet word boundary near the cut.
         */
        fun nextAudio(): FloatArray {
            val audio = utterance.samples
            val end =
                if (audio.size - offset <= maximumSliceSamples) audio.size
                else
                    quietCut(
                        audio,
                        minOf(offset + maximumSliceSamples, audio.size - SAMPLE_RATE * 2),
                    )
            val part = audio.copyOfRange(offset, end)
            offset = end
            return part
        }

        fun append(part: String) {
            if (part.isBlank()) return
            if (words.isNotEmpty()) words.append(' ')
            words.append(part.trim())
        }

        fun text(): String = words.toString()

        private fun quietCut(audio: FloatArray, limit: Int): Int {
            val step = SAMPLE_RATE / 100
            val window = step * 8
            val earliest = limit - SAMPLE_RATE * 2
            var energy = 0.0
            for (i in limit - window until limit) energy += audio[i] * audio[i]
            var best = energy
            var end = limit - window / 2
            // Find a quiet interval between words. A single near-zero sample also occurs in speech.
            for (right in limit - step downTo earliest step step) {
                for (i in right until right + step) {
                    energy += audio[i - window] * audio[i - window] - audio[i] * audio[i]
                }
                if (energy < best) {
                    end = right - window / 2
                    best = energy
                }
            }
            return end
        }

        companion object {
            /**
             * Ordinary sentences keep their context. Long turns yield after at most six seconds of
             * audio.
             */
            const val maximumSliceSamples = SAMPLE_RATE * 6
        }
    }
}

/**
 * Bounded audio for one recognizer. At most two Stop checks run between waiting chat slices. Tail
 * and short checks alternate when both are pending. Turns retain transcript order.
 */
class RecognizerQueue {
    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private var tail: RecognizerJob.StopCheck? = null
    private val shortSounds = ArrayDeque<RecognizerJob.StopCheck>()
    private val turns = ArrayDeque<RecognizerJob.Turn>()
    private var continuation: RecognizerJob.Turn? = null
    private var checksSinceTurn = 0
    private var lastWasTail = false
    private var queuedSamples = 0
    private var isClosed = false

    /** Queues a check of the latest audio, replacing any that's still waiting. */
    fun checkTail(samples: FloatArray, lastSpeechSample: Long = 0L) = add {
        tail = RecognizerJob.StopCheck(samples, lastSpeechSample)
    }

    /** Queues a sound too short to be a turn, to check for Stop. */
    fun checkShort(samples: FloatArray, lastSpeechSample: Long = 0L): Boolean = lock.withLock {
        if (isClosed || shortSounds.size >= 8 || samples.size > SAMPLE_RATE * 2) return false
        shortSounds.addLast(RecognizerJob.StopCheck(samples, lastSpeechSample))
        ready.signal()
        true
    }

    fun addTurn(utterance: Utterance): Boolean = lock.withLock {
        if (
            isClosed || turns.size >= 8 || utterance.samples.size > SAMPLE_RATE * 45 - queuedSamples
        )
            return false
        queuedSamples += utterance.samples.size
        turns.addLast(RecognizerJob.Turn(utterance))
        ready.signal()
        true
    }

    /** Only the recognizer returns its current turn; it stays ahead of newer chat turns. */
    fun continueTurn(turn: RecognizerJob.Turn) = add { continuation = turn }

    /**
     * The next job, waiting up to [timeoutMillis] for one. Null when the wait ran out or the queue
     * is closed.
     */
    fun take(timeoutMillis: Long): RecognizerJob? = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!isClosed) {
            next()?.let {
                return it
            }
            if (left <= 0) return null
            left = ready.awaitNanos(left)
        }
        null
    }

    /** True while any chat turn waits for the recognizer. */
    val hasTurns: Boolean
        get() = lock.withLock { continuation != null || turns.isNotEmpty() }

    /** Drops everything waiting and wakes [take], which then returns null. */
    fun close() = lock.withLock {
        isClosed = true
        tail = null
        shortSounds.clear()
        turns.clear()
        queuedSamples = 0
        continuation = null
        ready.signalAll()
    }

    private fun add(change: () -> Unit) = lock.withLock {
        if (isClosed) return@withLock
        change()
        ready.signal()
    }

    private fun next(): RecognizerJob? {
        val checksWaiting = tail != null || shortSounds.isNotEmpty()
        if (!checksWaiting || checksSinceTurn >= 2) {
            val turn =
                continuation
                    ?: turns.removeFirstOrNull()?.also {
                        queuedSamples -= it.utterance.samples.size
                    }
            if (turn != null) {
                continuation = null
                checksSinceTurn = 0
                return turn
            }
        }
        if (!checksWaiting) return null
        checksSinceTurn = minOf(2, checksSinceTurn + 1)
        if (shortSounds.isNotEmpty() && (lastWasTail || tail == null)) {
            lastWasTail = false
            return shortSounds.removeFirst()
        }
        lastWasTail = true
        return requireNotNull(tail).also { tail = null }
    }
}

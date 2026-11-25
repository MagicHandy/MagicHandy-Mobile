package com.magichandy.mobile.voice

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Something for the recognizer to hear. */
sealed interface RecognizerJob {
    /** Audio to check for a spoken Stop only. Its words never reach chat. */
    class StopCheck(val samples: FloatArray) : RecognizerJob

    /** A turn of speech for chat, checked for Stop first. */
    class Turn(val utterance: Utterance) : RecognizerJob
}

/**
 * The recognizer's work, Stop checks first (docs/voice-design.md §6): the latest tail check, then
 * short sounds, then chat turns in the order they were heard. Only the latest tail check is kept,
 * since it covers the audio of any before it. Safe to use from any thread.
 */
class RecognizerQueue {
    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private var tail: FloatArray? = null
    private val shortSounds = ArrayDeque<FloatArray>()
    private val turns = ArrayDeque<Utterance>()
    private var isClosed = false

    /** Queues a check of the latest audio, replacing any that's still waiting. */
    fun checkTail(samples: FloatArray) = add { tail = samples }

    /** Queues a sound too short to be a turn, to check for Stop. */
    fun checkShort(samples: FloatArray) = add { shortSounds.addLast(samples) }

    fun addTurn(utterance: Utterance) = add { turns.addLast(utterance) }

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
        get() = lock.withLock { turns.isNotEmpty() }

    /** Drops everything waiting and wakes [take], which then returns null. */
    fun close() = lock.withLock {
        isClosed = true
        tail = null
        shortSounds.clear()
        turns.clear()
        ready.signalAll()
    }

    private fun add(change: () -> Unit) = lock.withLock {
        if (isClosed) return@withLock
        change()
        ready.signal()
    }

    private fun next(): RecognizerJob? {
        tail?.let {
            tail = null
            return RecognizerJob.StopCheck(it)
        }
        shortSounds.removeFirstOrNull()?.let {
            return RecognizerJob.StopCheck(it)
        }
        return turns.removeFirstOrNull()?.let(RecognizerJob::Turn)
    }
}

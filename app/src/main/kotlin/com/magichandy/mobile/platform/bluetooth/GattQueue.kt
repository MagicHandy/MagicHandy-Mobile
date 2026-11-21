package com.magichandy.mobile.platform.bluetooth

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** One outstanding native call per generation. After timeout, a late callback has no successor. */
internal class GattQueue(private val retire: () -> Unit) {
    private class Pending(val key: String, val reply: CompletableDeferred<ByteArray>)

    private val serial = Mutex()
    private val pending = AtomicReference<Pending?>()
    @Volatile private var closed = false

    private val stops = MutableStateFlow(0)

    private suspend fun acquire(priority: Boolean) {
        while (true) {
            if (!priority) stops.first { it == 0 }
            serial.lock()
            if (priority || stops.value == 0) return
            serial.unlock()
        }
    }

    suspend fun execute(
        key: String,
        timeout: Duration,
        priority: Boolean = false,
        issue: () -> Boolean,
    ): ByteArray {
        if (priority) stops.update { it + 1 }
        return try {
            withTimeoutOrNull(timeout) {
                acquire(priority)
                try {
                    submit(key, false, issue)
                } finally {
                    serial.unlock()
                }
            }
                ?: run {
                    if (priority && !closed) retire()
                    throw IOException("Bluetooth operation timed out")
                }
        } finally {
            if (priority) stops.update { it - 1 }
        }
    }

    /** Optional GATT operations never join the queue in front of a Stop or a motion write. */
    suspend fun tryOptional(key: String, timeout: Duration, issue: () -> Boolean): ByteArray? {
        if (stops.value != 0 || !serial.tryLock()) return null
        return try {
            if (closed || stops.value != 0) null
            else withTimeoutOrNull(timeout) { submit(key, true, issue) }
        } finally {
            serial.unlock()
        }
    }

    private suspend fun submit(key: String, optional: Boolean, issue: () -> Boolean): ByteArray? {
        if (closed) throw IOException("Bluetooth link closed")
        val slot = Pending(key, CompletableDeferred())
        check(pending.compareAndSet(null, slot))
        return try {
            if (closed) throw IOException("Bluetooth link closed")
            if (!issue()) {
                if (optional) return null
                throw IOException("Bluetooth operation refused")
            }
            try {
                slot.reply.await()
            } catch (failure: GattStatus) {
                // This native callback settled the operation. A refused optional characteristic
                // supplies no reading, while the rest of this GATT generation remains usable.
                if (optional) null else throw failure
            }
        } catch (e: FencedGatt) {
            throw e
        } catch (e: Throwable) {
            if (!closed) retire()
            throw e
        } finally {
            pending.compareAndSet(slot, null)
        }
    }

    fun complete(key: String, status: Int, bytes: ByteArray = byteArrayOf()) {
        val slot = pending.get()?.takeIf { it.key == key } ?: return
        if (status == 0) slot.reply.complete(bytes)
        else slot.reply.completeExceptionally(GattStatus(status))
    }

    fun close() {
        closed = true
        pending
            .getAndSet(null)
            ?.reply
            ?.completeExceptionally(IOException("Bluetooth generation retired"))
    }
}

internal class GattStatus(val status: Int) : IOException("Bluetooth operation failed ($status)")

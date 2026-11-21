package com.magichandy.mobile.platform

import com.magichandy.mobile.platform.bluetooth.FencedGatt
import com.magichandy.mobile.platform.bluetooth.GattQueue
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class GattQueueTest {
    @Test
    fun `an optional read skips a busy link and a settled read refusal leaves priority Stop usable`() =
        runTest {
            val queue = GattQueue { error("a settled optional read retired the link") }
            val read = async { queue.tryOptional("battery", 500.milliseconds) { true } }
            runCurrent()
            assertEquals(
                null,
                queue.tryOptional("second-read", 500.milliseconds) {
                    error("queued optional read")
                },
            )
            var sent = false
            val stop = async {
                queue.execute("write", 300.milliseconds, priority = true) {
                    sent = true
                    true
                }
            }
            runCurrent()
            queue.complete("battery", 5)
            assertEquals(null, read.await())
            runCurrent()
            assertTrue(sent)
            queue.complete("write", 0)
            stop.await()
            assertEquals(null, queue.tryOptional("battery", 500.milliseconds) { false })
        }

    @Test
    fun `a timed out optional native read retires its generation and a late callback cannot satisfy its successor`() =
        runTest {
            val holder = java.util.concurrent.atomic.AtomicReference<GattQueue>()
            var retires = 0
            val old = GattQueue {
                retires++
                holder.get().close()
            }
                .also(holder::set)
            val reading = async { old.tryOptional("battery", 100.milliseconds) { true } }
            runCurrent()
            advanceTimeBy(101)
            assertEquals(null, reading.await())
            assertEquals(1, retires)
            val fresh = GattQueue { error("successor unexpectedly retired") }
            val current = async { fresh.execute("battery", 100.milliseconds) { true } }
            runCurrent()
            old.complete("battery", 0, byteArrayOf(7))
            runCurrent()
            assertFalse(current.isCompleted)
            fresh.complete("battery", 0, byteArrayOf(42))
            assertContentEquals(byteArrayOf(42), current.await())
        }

    @Test
    fun `only a matching callback releases a slot and a late callback cannot release its successor`() =
        runTest {
            val owner = java.util.concurrent.atomic.AtomicReference<GattQueue>()
            var retires = 0
            val queue = GattQueue {
                retires++
                owner.get().close()
            }
                .also(owner::set)
            val issued = mutableListOf<String>()
            val first = async {
                queue.execute("a", 100.milliseconds) {
                    issued += "a"
                    true
                }
            }
            val next = async {
                runCatching {
                    queue.execute("b", 100.milliseconds) {
                        issued += "b"
                        true
                    }
                }
            }
            runCurrent()
            queue.complete("b", 0)
            assertEquals(listOf("a"), issued)
            assertFalse(first.isCompleted)
            queue.complete("a", 0)
            first.await()
            runCurrent()
            assertEquals(listOf("a", "b"), issued)
            advanceTimeBy(101)
            assertIs<IOException>(next.await().exceptionOrNull())
            queue.complete("b", 0)
            assertEquals(1, retires)
            assertFailsWith<IOException> {
                queue.execute("b", 100.milliseconds) { error("retired generation issued a write") }
            }
        }

    @Test
    fun `a queued stale frame skips native submission and leaves Stop usable`() = runTest {
        val queue = GattQueue { error("unsent frame retired the healthy link") }
        val first = async { queue.execute("read", 100.milliseconds) { true } }
        val stale = async {
            runCatching { queue.execute("write", 100.milliseconds) { throw FencedGatt() } }
        }
        runCurrent()
        queue.complete("read", 0)
        first.await()
        assertIs<FencedGatt>(stale.await().exceptionOrNull())
        val stop = async { queue.execute("stop", 100.milliseconds) { true } }
        runCurrent()
        queue.complete("stop", 0)
        assertContentEquals(byteArrayOf(), stop.await())
    }

    @Test
    fun `priority Stop overtakes queued reads and a stuck native operation retires on the Stop deadline`() =
        runTest {
            val owner = java.util.concurrent.atomic.AtomicReference<GattQueue>()
            var retired = 0
            val queue = GattQueue {
                retired++
                owner.get().close()
            }
                .also(owner::set)
            val issued = mutableListOf<String>()
            fun operation(name: String, urgent: Boolean = false) = async {
                runCatching {
                    queue.execute(name, (if (urgent) 100 else 1000).milliseconds, urgent) {
                        issued += name
                        true
                    }
                }
            }
            val read = operation("read")
            val later = operation("later")
            val stop = operation("stop", true)
            runCurrent()
            assertEquals(listOf("read"), issued)
            queue.complete("read", 0)
            runCurrent()
            assertEquals(listOf("read", "stop"), issued)
            queue.complete("stop", 0)
            read.await().getOrThrow()
            stop.await().getOrThrow()
            runCurrent()
            assertEquals(listOf("read", "stop", "later"), issued)
            val urgent = operation("stuck-stop", true)
            advanceTimeBy(101)
            runCurrent()
            assertIs<IOException>(urgent.await().exceptionOrNull())
            assertIs<IOException>(later.await().exceptionOrNull())
            assertEquals(1, retired)
        }

    @Test
    fun `an optional priority hint cannot invalidate Stop evidence after a permission or binder change`() {
        var attempts = 0
        val link =
            com.magichandy.mobile.platform.bluetooth.AndroidGattLink(
                com.magichandy.mobile.transport.ble.GattProfile.Coyote,
                lost = {},
                applyPriority = {
                    attempts++
                    if (attempts == 1) throw SecurityException("synthetic permission")
                    throw IllegalStateException("synthetic retired link")
                },
            )
        link.setActive(true)
        link.setActive(false)
        assertEquals(2, attempts)
    }
}

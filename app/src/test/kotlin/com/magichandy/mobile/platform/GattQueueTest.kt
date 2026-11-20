package com.magichandy.mobile.platform

import com.magichandy.mobile.platform.bluetooth.*
import java.io.IOException
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class GattQueueTest {
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
}

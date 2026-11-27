package com.magichandy.mobile.voice

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Downloads resume, and a file takes its final name only once its checksum matches. */
internal class ModelStoreTest {
    @TempDir lateinit var root: File

    private val content = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val model =
        ModelFile(
            "parakeet/encoder.onnx",
            "https://example.test/encoder",
            content.size.toLong(),
            sha256(content),
        )

    @Test
    fun `a download is checked and takes its final name`() = runBlocking {
        val server = FakeServer(content)
        val store = ModelStore(root, server)
        var last = 0L
        store.download(listOf(model)) { done, _ -> last = done }
        assertTrue(store.has(model))
        assertArrayEquals(content, store.file(model).readBytes())
        assertEquals(content.size.toLong(), last)
        assertEquals(listOf(0L), server.requestedFrom)
    }

    @Test
    fun `a partial download resumes from where it stopped`() = runBlocking {
        val server = FakeServer(content)
        val store = ModelStore(root, server)
        partFile().apply {
            parentFile?.mkdirs()
            writeBytes(content.copyOf(80_000))
        }
        store.download(listOf(model)) { _, _ -> }
        assertTrue(store.has(model))
        assertArrayEquals(content, store.file(model).readBytes())
        assertEquals(listOf(80_000L), server.requestedFrom)
    }

    @Test
    fun `a server that ignores the range starts the file over`() = runBlocking {
        val server = FakeServer(content, honoursRange = false)
        val store = ModelStore(root, server)
        partFile().apply {
            parentFile?.mkdirs()
            writeBytes(content.copyOf(50_000))
        }
        store.download(listOf(model)) { _, _ -> }
        assertArrayEquals(content, store.file(model).readBytes())
    }

    @Test
    fun `a file that doesn't match its checksum is deleted, not kept`() {
        val tampered = content.copyOf().also { it[1_000] = (it[1_000] + 1).toByte() }
        val store = ModelStore(root, FakeServer(tampered))
        assertThrows<IOException> { runBlocking { store.download(listOf(model)) { _, _ -> } } }
        assertFalse(store.has(model))
        assertFalse(partFile().exists())
    }

    @Test
    fun `a refused request leaves nothing behind`() {
        val store =
            ModelStore(root, fetcher = { _, _ -> Fetched(404, ByteArrayInputStream(ByteArray(0))) })
        assertThrows<IOException> { runBlocking { store.download(listOf(model)) { _, _ -> } } }
        assertFalse(store.has(model))
    }

    @Test
    fun `a part that is already whole is checked and kept without a request`() = runBlocking {
        val server = FakeServer(content)
        val store = ModelStore(root, server)
        partFile().apply {
            parentFile?.mkdirs()
            writeBytes(content)
        }
        store.download(listOf(model)) { _, _ -> }
        assertTrue(store.has(model))
        assertEquals(emptyList<Long>(), server.requestedFrom)
    }

    @Test
    fun `a part the server can't continue starts over`() = runBlocking {
        val server = FakeServer(content, refusesRanges = true)
        val store = ModelStore(root, server)
        partFile().apply {
            parentFile?.mkdirs()
            writeBytes(content.copyOf(80_000))
        }
        store.download(listOf(model)) { _, _ -> }
        assertArrayEquals(content, store.file(model).readBytes())
        assertEquals(listOf(80_000L, 0L), server.requestedFrom)
    }

    @Test
    fun `a download needs room for itself and some to spare`() {
        val server = FakeServer(content)
        val store = ModelStore(root, server, freeBytes = { ModelStore.HEADROOM_BYTES })
        val refused =
            assertThrows<IOException> { runBlocking { store.download(listOf(model)) { _, _ -> } } }
        // The need rounds up and the room down, so a need never reads as met.
        assertEquals(
            "Not enough space: this needs 501 MB free, and the phone has 500 MB.",
            refused.message,
        )
        assertEquals(emptyList<Long>(), server.requestedFrom)
    }

    @Test
    fun `a part longer than its file counts the whole file against the space`() {
        partFile().apply {
            parentFile?.mkdirs()
            writeBytes(content + ByteArray(1_000))
        }
        val server = FakeServer(content)
        val store =
            ModelStore(root, server, freeBytes = { ModelStore.HEADROOM_BYTES + content.size / 2 })
        assertThrows<IOException> { runBlocking { store.download(listOf(model)) { _, _ -> } } }
        assertEquals(emptyList<Long>(), server.requestedFrom)
    }

    @Test
    fun `a failed download keeps its part when there's room`() {
        val store = ModelStore(root, FakeServer(content, failAfter = 80_000))
        assertThrows<IOException> { runBlocking { store.download(listOf(model)) { _, _ -> } } }
        assertEquals(80_000L, partFile().length())
    }

    @Test
    fun `a failed download on a nearly full disk gives its space back`() {
        // Room when the download starts; almost none once the write fails.
        val answers = ArrayDeque(listOf(Long.MAX_VALUE))
        val store =
            ModelStore(
                root,
                FakeServer(content, failAfter = 80_000),
                freeBytes = { answers.removeFirstOrNull() ?: 1_000L },
            )
        assertThrows<IOException> { runBlocking { store.download(listOf(model)) { _, _ -> } } }
        assertFalse(partFile().exists())
    }

    @Test
    fun `a server that sends too much is cut off`() {
        val store = ModelStore(root, FakeServer(content + ByteArray(100_000)))
        assertThrows<IOException> { runBlocking { store.download(listOf(model)) { _, _ -> } } }
        assertFalse(store.has(model))
        assertFalse(partFile().exists())
    }

    @Test
    fun `deleting removes finished and partial files`() = runBlocking {
        val store = ModelStore(root, FakeServer(content))
        store.download(listOf(model)) { _, _ -> }
        partFile().writeBytes(ByteArray(10))
        store.delete(listOf(model))
        assertFalse(store.file(model).exists())
        assertFalse(partFile().exists())
    }

    @Test
    fun resumeWaitsForTheCancelledWriterAndVerifiesTheActualFile() = runBlocking {
        val bytes = "abcdef".encodeToByteArray()
        val sha =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        val model = ModelFile("fake/data", "https://example.invalid/local-test", 6, sha)
        val oldRead = CountDownLatch(1)
        val newRead = CountDownLatch(1)
        val resumeOld = CountDownLatch(1)
        val resumeNew = CountDownLatch(1)
        fun source(old: Boolean) =
            object : InputStream() {
                var call = 0

                override fun read(): Int = error("Buffered read expected")

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val part =
                        if (old) {
                            when (call++) {
                                0 -> "ab"
                                1 -> {
                                    oldRead.countDown()
                                    check(resumeOld.await(6, TimeUnit.SECONDS))
                                    "cd"
                                }
                                else -> return -1
                            }
                        } else {
                            if (call++ > 0) return -1
                            newRead.countDown()
                            check(resumeNew.await(6, TimeUnit.SECONDS))
                            "cdef"
                        }
                    part.encodeToByteArray().copyInto(b, off)
                    return part.length
                }
            }
        val store =
            ModelStore(
                root,
                Fetcher { _, from ->
                    check(from in listOf(0L, 2L))
                    Fetched(if (from == 0L) 200 else 206, source(from == 0L))
                },
            )
        val first = launch(Dispatchers.IO) { store.download(listOf(model)) { _, _ -> } }
        try {
            check(oldRead.await(5, TimeUnit.SECONDS))
            first.cancel()
            val second = async(Dispatchers.IO) { store.download(listOf(model)) { _, _ -> } }
            assertFalse(newRead.await(100, TimeUnit.MILLISECONDS))
            resumeOld.countDown()
            check(newRead.await(5, TimeUnit.SECONDS))
            first.join()
            resumeNew.countDown()
            second.await()
            assertEquals("abcdef", store.file(model).readText())
            assertTrue(store.has(model))
        } finally {
            resumeOld.countDown()
            resumeNew.countDown()
            first.cancel()
        }
    }

    private fun partFile() = File(root, model.path + ".part")

    /**
     * Serves [body], from the requested byte when [honoursRange]. With [refusesRanges] it answers
     * any range with 416, as for a file that changed since the part began. With [failAfter] the
     * connection drops after that many bytes.
     */
    private class FakeServer(
        private val body: ByteArray,
        private val honoursRange: Boolean = true,
        private val refusesRanges: Boolean = false,
        private val failAfter: Int? = null,
    ) : Fetcher {
        val requestedFrom = mutableListOf<Long>()

        override suspend fun open(url: String, from: Long): Fetched {
            requestedFrom += from
            if (from > 0 && refusesRanges) return Fetched(416, ByteArrayInputStream(ByteArray(0)))
            if (from > 0 && honoursRange) {
                return Fetched(206, ranged(from))
            }
            val drop = failAfter ?: return Fetched(200, ByteArrayInputStream(body))
            return Fetched(200, DroppingStream(body.copyOf(drop)))
        }

        private fun ranged(from: Long): InputStream =
            ByteArrayInputStream(body, from.toInt(), body.size - from.toInt())
    }

    /** Delivers [data], then fails as a dropped connection does. */
    private class DroppingStream(private val data: ByteArray) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (index >= data.size) throw IOException("Connection reset")
            return data[index++].toInt() and BYTE_MASK
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index >= data.size) throw IOException("Connection reset")
            val count = minOf(length, data.size - index)
            data.copyInto(buffer, offset, index, index + count)
            index += count
            return count
        }

        private companion object {
            const val BYTE_MASK = 0xff
        }
    }

    private companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}

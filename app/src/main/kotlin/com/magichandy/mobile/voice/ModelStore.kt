package com.magichandy.mobile.voice

import com.magichandy.mobile.transport.http.HttpsOnly
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What a request returned: its status and its body. Closing it ends the request. */
class Fetched(val code: Int, val body: InputStream, private val onClose: () -> Unit = {}) :
    Closeable {
    override fun close() {
        try {
            body.close()
        } finally {
            onClose()
        }
    }
}

/** Opens [url], asking for the bytes from [from] on when [from] is above zero. */
fun interface Fetcher {
    suspend fun open(url: String, from: Long): Fetched
}

/**
 * Downloads over HTTPS only, following redirects such as Hugging Face's and GitHub's to their file
 * hosts: the check runs on every hop, so a redirect can't lead to plain HTTP.
 */
class HttpsFetcher(
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .addNetworkInterceptor(HttpsOnly)
            .followRedirects(true)
            .followSslRedirects(false)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
) : Fetcher {
    override suspend fun open(url: String, from: Long): Fetched {
        val request =
            Request.Builder()
                .url(url)
                .apply { if (from > 0) header("Range", "bytes=$from-") }
                .build()
        val call = client.newCall(request)
        // Keep cancellation attached while execute or a body read is blocked. The file writer
        // remains on its owning IO coroutine until the read and its finally blocks have finished.
        val cancellation =
            CoroutineScope(currentCoroutineContext()).launch(
                Dispatchers.IO,
                start = CoroutineStart.UNDISPATCHED,
            ) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
        try {
            val response = call.execute()
            return Fetched(response.code, response.body.byteStream()) {
                try {
                    response.close()
                } finally {
                    cancellation.cancel()
                }
            }
        } catch (failure: Throwable) {
            cancellation.cancel()
            throw failure
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
    }
}

/** Why a download stopped, when it wasn't the connection. */
sealed class DownloadFailure(message: String) : IOException(message) {
    /** The phone hasn't room for the download plus [ModelStore.HEADROOM_BYTES]. */
    class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) :
        DownloadFailure(
            "Not enough space: this needs " +
                ModelStore.size(neededBytes, roundUp = true) +
                " free, and the phone has " +
                ModelStore.size(freeBytes, roundUp = false) +
                "."
        )

    /** A file didn't match its checksum, so it was deleted. */
    class Damaged(path: String) :
        DownloadFailure("$path didn't match its checksum, so it was deleted")

    /** The server refused, or sent more than the file. */
    class Refused(detail: String) : DownloadFailure(detail)
}

/**
 * Model files on this phone. A download goes to a `.part` file, resumes where it stopped, and takes
 * its final name only once its SHA-256 matches, so a file with its final name is complete and
 * checked. [freeBytes] reports the space downloads may use; without it, space isn't checked.
 */
class ModelStore(
    val root: File,
    private val fetcher: Fetcher = HttpsFetcher(),
    private val freeBytes: () -> Long = { Long.MAX_VALUE },
) {
    private val writer = Mutex()

    fun file(model: ModelFile): File = File(root, model.path)

    fun has(model: ModelFile): Boolean = file(model).let { it.isFile && it.length() == model.bytes }

    fun hasAll(models: List<ModelFile>): Boolean = models.all(::has)

    /** The bytes of [models] on the phone, partial downloads included. */
    fun present(models: List<ModelFile>): Long = models.sumOf {
        if (has(it)) it.bytes else part(it).length().coerceAtMost(it.bytes)
    }

    /** The space [models] still need: whatever isn't here yet. */
    fun needed(models: List<ModelFile>): Long =
        models.filterNot(::has).sumOf {
            // A part longer than its file is deleted and fetched again, so it needs the whole file.
            val present = part(it).length()
            if (present > it.bytes) it.bytes else it.bytes - present
        }

    /**
     * Downloads every file in [models] that isn't here yet. [onProgress] gets the bytes present out
     * of the total, often; throttle before showing it.
     *
     * Throws [DownloadFailure.NotEnoughSpace] when there isn't room for the rest of the files plus
     * [HEADROOM_BYTES], which keeps space for the app's own files; [DownloadFailure.Damaged] when a
     * file doesn't match its checksum, and deletes it; and [IOException] when a request fails. A
     * partial file is kept to resume, unless the disk filled up.
     */
    suspend fun download(models: List<ModelFile>, onProgress: (done: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            writer.withLock {
                val needed = needed(models)
                val free = freeBytes()
                if (needed > 0 && free < needed + HEADROOM_BYTES) {
                    throw DownloadFailure.NotEnoughSpace(needed + HEADROOM_BYTES, free)
                }
                val total = models.sumOf { it.bytes }
                var done = models.filter(::has).sumOf { it.bytes }
                onProgress(done, total)
                for (model in models) {
                    if (has(model)) continue
                    val before = done
                    fetchOne(model) { present ->
                        done = before + present
                        onProgress(done, total)
                    }
                    done = before + model.bytes
                }
            }
        }

    /** Deletes [models], partial downloads included. */
    suspend fun delete(models: Collection<ModelFile>) =
        withContext(Dispatchers.IO) {
            writer.withLock {
                for (model in models) {
                    file(model).delete()
                    part(model).delete()
                }
            }
        }

    private fun part(model: ModelFile) = File(file(model).path + ".part")

    private suspend fun fetchOne(model: ModelFile, onPresent: (Long) -> Unit) {
        val target = file(model)
        val part = part(model)
        target.parentFile?.mkdirs()
        if (part.length() > model.bytes) part.delete()
        var present = if (part.isFile) part.length() else 0L
        onPresent(present)
        // A part that is already whole, from a download stopped just before its rename, needs no
        // request: asking for the bytes after its end would only be refused.
        if (present < model.bytes) {
            var response = fetcher.open(model.url, present)
            if (present > 0 && response.code == HTTP_RANGE_NOT_SATISFIABLE) {
                // The server can't continue this part, as when the file changed: start over.
                response.close()
                part.delete()
                present = 0
                response = fetcher.open(model.url, 0)
            }
            present = response.use { receive(it, model, part, present, onPresent) }
        }
        currentCoroutineContext().ensureActive()
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { digest.add(it) }
        if (
            present != model.bytes ||
                part.length() != model.bytes ||
                hex(digest.digest()) != model.sha256
        ) {
            part.delete()
            throw DownloadFailure.Damaged(model.path)
        }
        currentCoroutineContext().ensureActive()
        Files.move(
            part.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    /**
     * Writes [response]'s body to [part] and returns how many bytes the part then holds. A body
     * longer than [model] is cut off and its part deleted, so a faulty server can't fill the disk.
     */
    private suspend fun receive(
        response: Fetched,
        model: ModelFile,
        part: File,
        from: Long,
        onPresent: (Long) -> Unit,
    ): Long {
        var present = from
        when {
            // The server ignored the range and sent the whole file: start over.
            present > 0 && response.code == HTTP_OK -> {
                present = 0
            }
            response.code != HTTP_OK && response.code != HTTP_PARTIAL ->
                throw DownloadFailure.Refused("The download server answered " + response.code)
        }
        try {
            FileOutputStream(part, present > 0).use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = response.body.read(buffer)
                    currentCoroutineContext().ensureActive()
                    if (read < 0) break
                    present += read
                    if (present > model.bytes) {
                        part.delete()
                        throw DownloadFailure.Refused(
                            "The download server sent more than " + model.path
                        )
                    }
                    out.write(buffer, 0, read)
                    onPresent(present)
                }
            }
        } catch (failure: IOException) {
            // A full disk would starve the app's own files: give the space back.
            if (freeBytes() < LOW_SPACE_BYTES) part.delete()
            throw failure
        }
        return present
    }

    private suspend fun MessageDigest.add(input: InputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            update(buffer, 0, read)
        }
    }

    companion object {
        /** Space a download leaves free for the app's own files and the rest of the phone. */
        const val HEADROOM_BYTES = 500_000_000L

        /** Below this, a failed download deletes its partial file. */
        private const val LOW_SPACE_BYTES = 100_000_000L
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val BUFFER_BYTES = 1 shl 16
        private const val BYTES_PER_MB = 1_000_000.0
        private const val MB_PER_GB = 1_000.0
        private const val TENTHS = 10.0

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }

        /** [bytes] in MB or GB, rounded [roundUp] or down, so a need never reads as met. */
        fun size(bytes: Long, roundUp: Boolean): String {
            val megabytes = bytes.toDouble() / BYTES_PER_MB
            val shown = if (roundUp) ceil(megabytes) else floor(megabytes)
            if (shown < MB_PER_GB) return "${shown.toLong()} MB"
            val gigabytes = shown / MB_PER_GB * TENTHS
            val tenths = if (roundUp) ceil(gigabytes) else floor(gigabytes)
            return "%.1f GB".format(Locale.ROOT, tenths / TENTHS)
        }
    }
}

// Ported from internal/openaiauth/oauth.go. The Android browser owns the sign-in UI.
package com.magichandy.mobile.platform

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.BrowserLauncher
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class LoopbackBrowser(
    private val scope: CoroutineScope,
    private val launch: (Secret) -> Unit,
) : BrowserLauncher {
    constructor(
        context: Context,
        scope: CoroutineScope,
    ) : this(
        scope,
        { authorization ->
            CustomTabsIntent.Builder()
                .build()
                .apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                .launchUrl(context, authorization.reveal().toUri())
        },
    )

    private class Lease(val socket: ServerSocket) {
        val peer = AtomicReference<Socket?>()
        val uri = "http://127.0.0.1:" + socket.localPort + "/auth/callback"

        fun close() {
            socket.close()
            peer.getAndSet(null)?.close()
        }
    }

    private val pending = AtomicReference<Lease?>()
    private var expiry: Job? = null
    override val redirectUri: String
        get() {
            close()
            val lease =
                Lease(ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))))
            pending.set(lease)
            expiry = scope.launch {
                delay(2.minutes)
                if (pending.compareAndSet(lease, null)) lease.close()
            }
            return lease.uri
        }

    override fun close() {
        expiry?.cancel()
        pending.getAndSet(null)?.close()
    }

    override suspend fun openAndAwait(authorization: Secret): Secret? {
        val lease = pending.get() ?: return null
        val state = authorization.reveal().toHttpUrlOrNull()?.queryParameter("state") ?: return null
        return try {
            withContext(Dispatchers.Main.immediate) { launch(authorization) }
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { lease.close() }
                scope.launch(Dispatchers.IO) {
                    val result =
                        try {
                            receive(lease, state)
                        } catch (_: IOException) {
                            null
                        }
                    if (cont.isActive) cont.resumeWith(Result.success(result))
                }
            }
        } catch (_: android.content.ActivityNotFoundException) {
            null
        } finally {
            close()
        }
    }

    private fun receive(lease: Lease, state: String): Secret? {
        repeat(8) {
            val socket = lease.socket.accept()
            lease.peer.set(socket)
            if (lease.socket.isClosed) {
                socket.close()
                return null
            }
            socket.use { s ->
                s.soTimeout = 3000
                val reader = s.getInputStream().buffered()
                val first = line(reader, 8192) ?: return@use
                var size = first.length
                var host: String? = null
                while (size < 12288) {
                    val header = line(reader, 4096) ?: return@use
                    if (header.isEmpty()) break
                    size += header.length + 2
                    if (header.startsWith("Host:", true)) {
                        if (host != null) return@use
                        host = header.substringAfter(':').trim()
                    }
                }
                val parts = first.split(' ')
                val path = parts.getOrNull(1).orEmpty()
                val url = ("http://127.0.0.1:" + lease.socket.localPort + path).toHttpUrlOrNull()
                val valid =
                    size < 12288 &&
                        parts.size == 3 &&
                        parts[0] == "GET" &&
                        parts[2] in setOf("HTTP/1.0", "HTTP/1.1") &&
                        host == "127.0.0.1:" + lease.socket.localPort &&
                        path.startsWith("/auth/callback?") &&
                        url?.encodedPath == "/auth/callback" &&
                        url.queryParameterValues("state") == listOf(state)
                val body = if (valid) "Return to MagicHandy." else "Invalid request."
                val status = if (valid) "200 OK" else "400 Bad Request"
                s.getOutputStream()
                    .write(
                        ("HTTP/1.1 " +
                                status +
                                "\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: " +
                                body.length +
                                "\r\n\r\n" +
                                body)
                            .encodeToByteArray()
                    )
                if (valid) return Secret(requireNotNull(url).toString())
            }
        }
        return null
    }

    private fun line(input: InputStream, limit: Int): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= limit) {
            val ch = input.read()
            if (ch < 0) return null
            if (ch == 10) return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
            bytes.write(ch)
        }
        return null
    }
}

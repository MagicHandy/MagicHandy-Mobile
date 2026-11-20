// Ported from internal/transport/intiface_{session,protocol}.go at 1b80d72e.
package com.magichandy.mobile.transport.intiface

import com.magichandy.mobile.transport.providers.originClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*

internal class ButtplugSocket(private val lost: () -> Unit) : WebSocketListener() {
    private data class Pending(val reply: String, val result: CompletableDeferred<JsonObject>)

    private val replies = ConcurrentHashMap<Long, Pending>()
    private val sequence = AtomicLong()
    private val ready = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean()
    @Volatile private var socket: WebSocket? = null
    @Volatile var deviceIndex = -1

    suspend fun open(http: OkHttpClient, address: String) {
        val request = Request.Builder().url(address).build()
        socket = originClient(http, request.url).newWebSocket(request, this)
        try {
            withTimeoutOrNull(5.seconds) { ready.await() }
                ?: throw IOException("Bridge handshake timed out")
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        ready.complete(Unit)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        fail()
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        fail()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        fail()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (text.length > 65536) {
            fail()
            return
        }
        try {
            val messages = Json.parseToJsonElement(text).jsonArray
            require(messages.size in 1..64)
            for (item in messages) {
                val (kind, payload) = item.jsonObject.entries.single()
                val data = payload.jsonObject
                val id = data.getValue("Id").jsonPrimitive.long
                if (id == 0L) {
                    if (
                        kind == "DeviceRemoved" &&
                            data["DeviceIndex"]?.jsonPrimitive?.int == deviceIndex
                    )
                        fail()
                } else
                    replies.remove(id)?.let { pending ->
                        if (pending.reply == kind) pending.result.complete(data)
                        else
                            pending.result.completeExceptionally(
                                IOException("Bridge command rejected")
                            )
                    }
            }
        } catch (_: RuntimeException) {
            fail()
        }
    }

    suspend fun request(
        kind: String,
        reply: String = "Ok",
        data: JsonObject = JsonObject(emptyMap()),
        allowed: () -> Boolean = { true },
    ): JsonObject {
        val id = sequence.incrementAndGet()
        if (closed.get() || replies.size >= 16 || id > 0xffff_ffffL)
            throw IOException("Bridge unavailable")
        val pending = Pending(reply, CompletableDeferred())
        replies[id] = pending
        try {
            val message =
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put(kind, JsonObject(data + ("Id" to JsonPrimitive(id))))
                        }
                    )
                )
            if (!allowed() || closed.get() || socket?.send(message.toString()) != true)
                throw IOException("Bridge submission refused")
            return withTimeoutOrNull(1500) { pending.result.await() }
                ?: throw IOException("Bridge response timed out")
        } finally {
            replies.remove(id)
        }
    }

    private fun fail() {
        if (close()) lost()
    }

    fun close(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        ready.completeExceptionally(IOException("Bridge closed"))
        replies.values.forEach { it.result.completeExceptionally(IOException("Bridge closed")) }
        socket?.cancel()
        return true
    }
}

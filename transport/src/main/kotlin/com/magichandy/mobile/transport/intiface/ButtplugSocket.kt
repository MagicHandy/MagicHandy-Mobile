// Ported from internal/transport/intiface_session.go at 1b80d72e.
package com.magichandy.mobile.transport.intiface

import com.magichandy.mobile.transport.providers.originClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*

internal class ButtplugSocket(
    private val lost: () -> Unit,
    private val changed: (Int) -> Unit = {},
) : WebSocketListener() {
    internal data class Reply(val data: JsonObject, val elapsed: kotlin.time.Duration)

    internal class Pending(val expected: String) {
        val result = CompletableDeferred<Reply>()
        val sent = TimeSource.Monotonic.markNow()
    }

    // The only critical section covers registration, the last admission check and OkHttp's
    // nonblocking send. Never hold it while awaiting a peer or a coroutine.
    val wire = Any()
    private val pending = mutableMapOf<Long, Pending>()
    private var next = 0L
    private val ready = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean()
    @Volatile private var socket: WebSocket? = null

    suspend fun open(http: OkHttpClient, address: String) {
        val req = Request.Builder().url(address).build()
        socket = originClient(http, req.url).newWebSocket(req, this)
        try {
            withTimeoutOrNull(5000) { ready.await() }
                ?: throw IOException("Bridge handshake timed out")
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        ready.complete(Unit)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail()

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = fail()

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail()

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            require(text.length <= 65536)
            val batch = Json.parseToJsonElement(text).jsonArray
            require(batch.size in 1..64)
            for (item in batch) {
                val (kind, payload) = item.jsonObject.entries.single()
                val data = payload.jsonObject
                val id = data.getValue("Id").jsonPrimitive.long
                if (id == 0L) {
                    if (kind == "DeviceRemoved" || kind == "DeviceAdded")
                        changed(data.getValue("DeviceIndex").jsonPrimitive.int)
                } else {
                    val slot = synchronized(wire) { pending.remove(id) } ?: continue
                    if (slot.expected == kind)
                        slot.result.complete(Reply(data, slot.sent.elapsedNow()))
                    else slot.result.completeExceptionally(IOException("Bridge command rejected"))
                }
            }
        } catch (_: RuntimeException) {
            fail()
        }
    }

    inner class Ticket(private val id: Long, private val slot: Pending) {
        suspend fun await(timeoutMillis: Long): JsonObject =
            try {
                val received =
                    if (slot.result.isCompleted) slot.result.await()
                    else
                        withTimeoutOrNull(timeoutMillis.milliseconds - slot.sent.elapsedNow()) {
                            slot.result.await()
                        } ?: throw IOException("Bridge response timed out")
                if (received.elapsed > timeoutMillis.milliseconds)
                    throw IOException("Bridge response timed out")
                received.data
            } finally {
                synchronized(wire) { pending.remove(id, slot) }
            }
    }

    fun begin(
        kind: String,
        reply: String = "Ok",
        data: JsonObject = JsonObject(emptyMap()),
        allowed: () -> Boolean = { true },
    ): Ticket =
        synchronized(wire) {
            if (
                closed.get() ||
                    next >= 0xffffffffL ||
                    pending.size >= (if (kind in stopKinds) 160 else 128) ||
                    !allowed()
            )
                throw IOException("Bridge submission refused")
            val id = ++next
            val slot = Pending(reply)
            val payload = buildJsonObject {
                put(kind, JsonObject(data + ("Id" to JsonPrimitive(id))))
            }
            pending[id] = slot
            if (socket?.send(JsonArray(listOf(payload)).toString()) != true) {
                pending.remove(id)
                throw IOException("Bridge write failed")
            }
            Ticket(id, slot)
        }

    suspend fun request(
        kind: String,
        reply: String = "Ok",
        data: JsonObject = JsonObject(emptyMap()),
        allowed: () -> Boolean = { true },
    ) = begin(kind, reply, data, allowed).await(1500)

    private fun fail() {
        if (close()) lost()
    }

    fun close(): Boolean {
        val waiters =
            synchronized(wire) {
                if (!closed.compareAndSet(false, true)) return false
                pending.values.toList().also { pending.clear() }
            }
        val failure = IOException("Bridge closed")
        ready.completeExceptionally(failure)
        waiters.forEach { it.result.completeExceptionally(failure) }
        socket?.cancel()
        return true
    }

    companion object {
        private val stopKinds = setOf("StopAllDevices", "StopDeviceCmd")
    }
}

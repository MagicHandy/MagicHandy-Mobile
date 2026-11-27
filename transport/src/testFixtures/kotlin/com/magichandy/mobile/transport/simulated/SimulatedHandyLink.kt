// Ported from internal/handyble/simulated.go at 1b80d72e.
package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.transport.ble.BluetoothLink
import com.magichandy.mobile.transport.ble.BluetoothWriteFenced
import com.magichandy.mobile.transport.ble.GattProfile
import com.magichandy.mobile.transport.handy.ProtoWriter
import com.magichandy.mobile.transport.handy.fields
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Byte-level peer used by the real firmware-4 session. Every reply carries the request ID. */
internal class SimulatedHandyLink(
    private val clockMillis: () -> Long = { 0 },
    private val stopped: () -> Unit,
) : BluetoothLink {
    private val closed = AtomicBoolean()
    private val notifications = Channel<ByteArray>(Channel.UNLIMITED)
    private var streamId = 0L
    private var playing = false
    private var stream = false
    private var pointCount = 0L

    override suspend fun requestMtu(bytes: Int): Int = minOf(bytes, 517)

    override suspend fun discover(service: String): Set<String> =
        if (service == GattProfile.Handy.service)
            setOf(GattProfile.Handy.write, GattProfile.Handy.notify)
        else emptySet()

    override suspend fun notifications(characteristic: String) = notifications.receiveAsFlow()

    override suspend fun read(characteristic: String): ByteArray =
        throw IOException("Characteristic unavailable")

    override suspend fun write(
        characteristic: String,
        bytes: ByteArray,
        withResponse: Boolean,
        admitted: () -> Boolean,
    ) {
        if (closed.get()) throw IOException("Simulated link retired")
        if (!admitted()) throw BluetoothWriteFenced()
        if (characteristic != GattProfile.Handy.write || !withResponse)
            throw IOException("Unexpected GATT operation")
        val envelope = fields(bytes)
        val payload =
            envelope.singleOrNull { it.number == 2 }?.data
                ?: throw IOException("Missing RPC request")
        val request = fields(payload)
        val id =
            request.singleOrNull { it.number == 2 }?.value ?: throw IOException("Missing RPC ID")
        val method =
            request.singleOrNull { it.number > 10 } ?: throw IOException("Missing RPC method")
        val body = fields(method.data ?: throw IOException("Invalid RPC body"))
        if (closed.get()) throw IOException("Simulated link retired")
        if (!admitted()) throw BluetoothWriteFenced()
        val accepted =
            when (method.number) {
                715,
                864 -> {
                    stream = false
                    pointCount = 0
                    stopped()
                    true
                }
                709,
                712,
                841 -> true
                860 -> {
                    streamId = requireNotNull(body.single { it.number == 1 }.value)
                    stream = true
                    pointCount = 0
                    true
                }
                861 -> {
                    val count = body.count { it.number == 1 && it.data != null }
                    val tail = body.lastOrNull { it.number == 3 }?.value
                    if (!stream || count == 0 || tail != pointCount + count - 1) false
                    else {
                        pointCount += count
                        true
                    }
                }
                863 -> stream && pointCount > 0
                else -> false
            }
        if (accepted)
            when (method.number) {
                715,
                860,
                864 -> playing = false
                863 -> playing = true
            }
        val reply =
            ProtoWriter()
                .apply {
                    uint(1, 3)
                    message(4) {
                        uint(1, id)
                        if (!accepted) message(2) { uint(1, 1) }
                        // Like firmware, answer RPC 715, which has no result type, with a blank
                        // response.
                        else if (method.number != 715)
                            message(method.number) {
                                if (method.number in 860..867)
                                    message(1) {
                                        uint(1, if (playing) 1 else 2)
                                        uint(10, streamId)
                                    }
                                if (method.number == 712) uint(1, clockMillis() and 0xffff_ffffL)
                            }
                    }
                }
                .bytes()
        if (notifications.trySend(reply).isFailure) throw IOException("Simulated link closed")
    }

    override fun retire() {
        if (closed.compareAndSet(false, true)) notifications.close()
    }
}

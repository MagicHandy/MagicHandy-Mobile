package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.transport.ble.BluetoothLink
import com.magichandy.mobile.transport.ble.GattProfile
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext

internal class HandyPeerLink : BluetoothLink {
    var retired = false
    val events = Channel<ByteArray>(Channel.UNLIMITED)
    val methods = mutableListOf<Int>()
    var replyIdDelta = 0
    var negotiation = true
    var blockSetup = false
    val nativeClose = CompletableDeferred<Unit>()
    var setupDelayMillis = 0L
    var denyDiscovery = false
    var omitClockReply = false
    var omitClockSetReply = false
    var clockMillis: Long? = null
    val playTimestamps = mutableListOf<Long?>()
    var failWrite = false
    var beforeAdmission: () -> Unit = {}

    override suspend fun requestMtu(bytes: Int): Int {
        assertEquals(517, bytes)
        if (!negotiation) throw IOException("synthetic")
        return bytes
    }

    override suspend fun discover(service: String): Set<String> {
        if (denyDiscovery) throw SecurityException("Synthetic permission revocation")
        return setOf(GattProfile.Handy.write, GattProfile.Handy.notify)
    }

    override suspend fun notifications(characteristic: String) = events.receiveAsFlow()

    override suspend fun write(
        characteristic: String,
        bytes: ByteArray,
        withResponse: Boolean,
        admitted: () -> Boolean,
    ) {
        assertTrue(withResponse)
        beforeAdmission()
        if (!admitted()) throw IOException("synthetic pre-write refusal")
        val packet = fields(requireNotNull(fields(bytes).single { it.number == 2 }.data))
        val method = packet.single { it.data != null }.number
        if (failWrite) throw IOException("synthetic write failure")
        methods += method
        if (method == 712 && omitClockReply) return
        if (method == 709 && omitClockSetReply) return
        if (method == 863)
            playTimestamps +=
                fields(requireNotNull(packet.single { it.number == 863 }.data))
                    .singleOrNull { it.number == 2 }
                    ?.value
        if (method == 860) delay(setupDelayMillis)
        if (method == 860 && blockSetup) withContext(NonCancellable) { nativeClose.await() }
        val id = requireNotNull(packet.single { it.number == 2 }.value)
        events.send(
            ProtoWriter()
                .apply {
                    uint(1, 3)
                    message(4) {
                        uint(1, id + replyIdDelta)
                        message(method) { if (method == 712) clockMillis?.let { uint(1, it) } }
                    }
                }
                .bytes()
        )
    }

    override suspend fun read(characteristic: String): ByteArray = error("Unexpected read")

    override fun retire() {
        nativeClose.complete(Unit)
        retired = true
        events.close()
    }
}

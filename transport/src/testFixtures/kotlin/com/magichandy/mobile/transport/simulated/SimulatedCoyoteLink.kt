package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.transport.ble.BluetoothLink
import com.magichandy.mobile.transport.ble.BluetoothWriteFenced
import com.magichandy.mobile.transport.ble.GattProfile
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** A byte-level Coyote 3.0 peer shared by the debug application and JVM tests. */
internal class SimulatedCoyoteLink(
    private val scope: CoroutineScope,
    private val output: (strengthA: Int, strengthB: Int, zero: Boolean) -> Unit,
) : BluetoothLink {
    private val changes = Channel<ByteArray>(8)
    private var delayed: Job? = null
    private var closed = false
    private var initializing = true
    private var ceilingA = 0
    private var ceilingB = 0
    private var a = 0
    private var b = 0

    override suspend fun requestMtu(bytes: Int) = 517

    override suspend fun discover(service: String) =
        setOf(GattProfile.Coyote.write, GattProfile.Coyote.notify)

    override suspend fun notifications(characteristic: String) = changes.receiveAsFlow()

    override suspend fun read(characteristic: String) = byteArrayOf()

    override suspend fun write(
        characteristic: String,
        bytes: ByteArray,
        withResponse: Boolean,
        admitted: () -> Boolean,
    ) {
        synchronized(this) {
            if (closed) throw IOException("Simulated link closed")
            if (!admitted()) throw BluetoothWriteFenced()
            when (bytes.firstOrNull()?.toInt()?.and(255)) {
                0xbf -> {
                    require(bytes.size == 7)
                    ceilingA = bytes[1].toInt() and 255
                    ceilingB = bytes[2].toInt() and 255
                }
                0xb0 -> frame(bytes)
                else -> throw IOException("Unknown simulated packet")
            }
        }
    }

    private fun frame(bytes: ByteArray) {
        require(bytes.size == 20)
        val control = bytes[1].toInt() and 255
        val modeA = control ushr 2 and 3
        val modeB = control and 3
        val zero = modeA == 3 && modeB == 3 && bytes[2].toInt() == 0 && bytes[3].toInt() == 0
        a = change(a, modeA, bytes[2].toInt() and 255, ceilingA)
        b = change(b, modeB, bytes[3].toInt() and 255, ceilingB)
        output(a, b, zero)
        if (control ushr 4 == 0) return
        delayed?.cancel()
        val report = byteArrayOf(0xb1.toByte(), (control ushr 4).toByte(), a.toByte(), b.toByte())
        if (initializing && zero) {
            initializing = false
            changes.trySend(report).getOrThrow()
            return
        }
        delayed = scope.launch {
            delay(55)
            changes.send(report)
        }
    }

    private fun change(current: Int, mode: Int, amount: Int, cap: Int) =
        when (mode) {
            0 -> current
            1 -> current + minOf(amount, 1)
            2 -> current - minOf(amount, 1)
            else -> amount
        }.coerceIn(0, cap)

    @Synchronized
    override fun retire() {
        closed = true
        delayed?.cancel()
        changes.close()
    }
}

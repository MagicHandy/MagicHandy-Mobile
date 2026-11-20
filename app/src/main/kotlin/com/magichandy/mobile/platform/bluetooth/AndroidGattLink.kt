package com.magichandy.mobile.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import com.magichandy.mobile.transport.ble.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class AndroidGattLink(private val profile: GattProfile, private val lost: () -> Unit) :
    BluetoothGattCallback(), BluetoothLink {
    private val native = AtomicReference<BluetoothGatt?>()
    private val connected = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean()
    private val queue = GattQueue(::retire)
    private val values = Channel<ByteArray>(64)
    private val ccc = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val service
        get() = UUID.fromString(profile.service)

    suspend fun connect(
        context: Context,
        device: BluetoothDevice,
        timeout: Duration,
    ): AndroidGattLink {
        try {
            val gatt =
                device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
                    ?: throw IOException("Bluetooth connection unavailable")
            if (!native.compareAndSet(null, gatt) || closed.get()) {
                gatt.close()
                throw IOException("Bluetooth generation closed")
            }
            withTimeoutOrNull(timeout) { connected.await() }
                ?: throw IOException("Bluetooth connection timed out")
            return this
        } catch (failure: Throwable) {
            retire()
            throw failure
        }
    }

    private fun gatt() =
        native.get()?.takeUnless { closed.get() }
            ?: throw IOException("Bluetooth generation unavailable")

    private fun characteristic(id: String) =
        gatt().getService(service)?.getCharacteristic(UUID.fromString(id))
            ?: throw IOException("Required characteristic missing")

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (closed.get()) return
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED)
            connected.complete(Unit)
        else if (
            status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED
        ) {
            val ready = connected.isCompleted
            connected.completeExceptionally(GattStatus(status))
            retire()
            if (ready) lost()
        }
    }

    override suspend fun requestMtu(bytes: Int): Int {
        val encoded = queue.execute("mtu", 2.seconds) { gatt().requestMtu(bytes) }
        return java.nio.ByteBuffer.wrap(encoded).int
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) =
        queue.complete("mtu", status, java.nio.ByteBuffer.allocate(4).putInt(mtu).array())

    override suspend fun discover(service: String): Set<String> {
        queue.execute("discover", 3.seconds) { gatt().discoverServices() }
        return gatt()
            .getService(UUID.fromString(service))
            ?.characteristics
            .orEmpty()
            .map { it.uuid.toString() }
            .toSet()
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
        queue.complete("discover", status)

    override suspend fun notifications(characteristic: String): Flow<ByteArray> {
        val c = characteristic(characteristic)
        val descriptor =
            c.getDescriptor(ccc) ?: throw IOException("Notification descriptor missing")
        try {
            queue.execute("notify", 2.seconds) {
                val g = gatt()
                if (!g.setCharacteristicNotification(c, true)) false
                else if (Build.VERSION.SDK_INT >= 33)
                    g.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                    ) == BluetoothStatusCodes.SUCCESS
                else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(descriptor)
                }
            }
        } catch (failure: IOException) {
            throw BluetoothSetupWriteFailure(failure)
        }
        return values.receiveAsFlow()
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (descriptor.uuid == ccc && descriptor.characteristic.uuid.toString() == profile.notify)
            queue.complete("notify", status)
    }

    private fun value(c: BluetoothGattCharacteristic, data: ByteArray) {
        if (
            !closed.get() &&
                c.uuid.toString() == profile.notify &&
                !values.trySend(data.copyOf()).isSuccess
        ) {
            retire()
            lost()
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) = value(characteristic, value)

    @Deprecated("Legacy Android callback")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        characteristic.value?.let { value(characteristic, it) }
    }

    override suspend fun write(
        characteristic: String,
        bytes: ByteArray,
        withResponse: Boolean,
        admitted: () -> Boolean,
    ) = writeCharacteristic(characteristic, bytes, withResponse, false, admitted)

    override suspend fun writeStop(
        characteristic: String,
        bytes: ByteArray,
        withResponse: Boolean,
        admitted: () -> Boolean,
    ) = writeCharacteristic(characteristic, bytes, withResponse, true, admitted)

    private suspend fun writeCharacteristic(
        characteristic: String,
        bytes: ByteArray,
        withResponse: Boolean,
        priority: Boolean,
        admitted: () -> Boolean,
    ) {
        require(bytes.size <= profile.maxBytes)
        val c = characteristic(characteristic)
        val type =
            if (withResponse && c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        queue.execute("write:" + c.uuid, 1.seconds, priority) {
            if (!admitted()) throw FencedGatt()
            val g = gatt()
            if (Build.VERSION.SDK_INT >= 33)
                g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
            else {
                c.writeType = type
                c.value = bytes
                g.writeCharacteristic(c)
            }
        }
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) = queue.complete("write:" + characteristic.uuid, status)

    override suspend fun read(characteristic: String): ByteArray {
        val c = characteristic(characteristic)
        return queue.execute("read:" + c.uuid, 2.seconds) { gatt().readCharacteristic(c) }
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) = queue.complete("read:" + characteristic.uuid, status, value.copyOf())

    @Deprecated("Legacy Android callback")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) =
        queue.complete(
            "read:" + characteristic.uuid,
            status,
            characteristic.value?.copyOf() ?: byteArrayOf(),
        )

    override fun setActive(active: Boolean) {
        native
            .get()
            ?.requestConnectionPriority(
                if (active) BluetoothGatt.CONNECTION_PRIORITY_HIGH
                else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            )
    }

    override fun retire() {
        if (!closed.compareAndSet(false, true)) return
        connected.completeExceptionally(IOException("Bluetooth generation retired"))
        queue.close()
        values.close(IOException("Bluetooth link closed"))
        native.getAndSet(null)?.let {
            try {
                it.close()
            } catch (_: SecurityException) {
                /* Revoked Bluetooth access. */
            }
        }
    }
}

internal class FencedGatt : IOException("Write admission expired")

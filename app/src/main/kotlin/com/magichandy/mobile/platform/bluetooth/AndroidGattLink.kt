package com.magichandy.mobile.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.transport.ble.BluetoothLink
import com.magichandy.mobile.transport.ble.BluetoothSetupWriteFailure
import com.magichandy.mobile.transport.ble.BluetoothWriteFenced
import com.magichandy.mobile.transport.ble.GattProfile
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class AndroidGattLink(
    private val profile: GattProfile,
    private val lost: () -> Unit,
    private val applyPriority: ((Int) -> Unit)? = null,
) : BluetoothGattCallback(), BluetoothLink {
    private val native = AtomicReference<BluetoothGatt?>()
    private val connected = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean()
    private val active = AtomicBoolean()
    @Volatile private var battery: Percent? = null
    private val queue = GattQueue(::retire)
    private val values = Channel<ByteArray>(64)
    private val ccc = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val notifyUuid = UUID.fromString(profile.notify)
    @Volatile
    private var writeLimit = 20 // The default ATT MTU is 23, including the three-byte header.
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
        val encoded =
            if (profile == GattProfile.Coyote)
                queue.tryOptional("mtu", 2.seconds) { gatt().requestMtu(bytes) }
            else queue.execute("mtu", 2.seconds) { gatt().requestMtu(bytes) }
        if (encoded == null) {
            // Coyote's 20-byte frames fit the default ATT MTU if negotiation was refused.
            gatt() // A timeout retires the generation and still fails setup.
            return 23
        }
        val mtu = java.nio.ByteBuffer.wrap(encoded).int
        writeLimit = (mtu - 3).coerceIn(0, profile.maxBytes)
        return mtu
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

    override fun onServiceChanged(gatt: BluetoothGatt) {
        if (closed.get() || native.get() !== gatt) return
        // The verified characteristics belong to the old service layout. Reconnect before use.
        retire()
        lost()
    }

    override suspend fun notifications(characteristic: String): Flow<ByteArray> {
        val c = characteristic(characteristic)
        if (c.uuid != notifyUuid) throw IOException("Unexpected notification characteristic")
        val subscription =
            when {
                c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else ->
                    throw IOException(
                        "Characteristic does not support notifications or indications"
                    )
            }
        val descriptor =
            c.getDescriptor(ccc) ?: throw IOException("Notification descriptor missing")
        try {
            queue.execute("notify", 2.seconds) {
                val g = gatt()
                if (!g.setCharacteristicNotification(c, true)) false
                else if (Build.VERSION.SDK_INT >= 33)
                    g.writeDescriptor(
                        descriptor,
                        subscription,
                    ) == BluetoothStatusCodes.SUCCESS
                else {
                    descriptor.value = subscription
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
        if (descriptor.uuid == ccc && descriptor.characteristic.uuid == notifyUuid)
            queue.complete("notify", status)
    }

    private fun value(c: BluetoothGattCharacteristic, data: ByteArray) {
        if (!closed.get() && c.uuid == notifyUuid && !values.trySend(data.copyOf()).isSuccess) {
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
        if (!admitted()) throw FencedGatt()
        if (bytes.size > writeLimit)
            throw IOException("Payload exceeds negotiated GATT write length")
        val c = characteristic(characteristic)
        val type = gattWriteType(c.properties, withResponse)
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

    override suspend fun readBattery(): Percent? {
        if (closed.get()) return null
        if (active.get()) return battery
        val g = gatt()
        val standard =
            g.getService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"))
                ?.getCharacteristic(UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"))
        val custom =
            if (profile == GattProfile.Coyote)
                g.getService(UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"))
                    ?.getCharacteristic(UUID.fromString("00001500-0000-1000-8000-00805f9b34fb"))
            else null
        val c = custom ?: standard ?: return null
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) return null
        val value =
            queue.tryOptional("read:" + c.uuid, 2.seconds) {
                !active.get() && gatt().readCharacteristic(c)
            } ?: return battery
        battery =
            value.singleOrNull()?.toUByte()?.toInt()?.takeIf { it <= 100 }?.let { Percent(it) }
        return battery
    }

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
        this.active.set(active)
        if (closed.get()) return
        val priority =
            if (active) BluetoothGatt.CONNECTION_PRIORITY_HIGH
            else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        try {
            if (applyPriority != null) applyPriority.invoke(priority)
            else native.get()?.requestConnectionPriority(priority)
        } catch (_: SecurityException) {
            // The radio permission can change after Stop has already been acknowledged.
        } catch (_: IllegalStateException) {
            // The connection may already have closed. This optional power hint supplies no Stop
            // evidence.
        }
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

internal typealias FencedGatt = BluetoothWriteFenced

/** Prefer the requested ATT write type. Device protocols establish Stop with their own reply. */
internal fun gattWriteType(properties: Int, withResponse: Boolean): Int {
    val acknowledged = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
    val unacknowledged = properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    return when {
        withResponse && acknowledged -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        unacknowledged -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        acknowledged -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else -> throw IOException("Characteristic is not writable")
    }
}

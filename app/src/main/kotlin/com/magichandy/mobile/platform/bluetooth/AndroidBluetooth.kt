package com.magichandy.mobile.platform.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.magichandy.mobile.core.api.ScanResultId
import com.magichandy.mobile.transport.ble.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

@SuppressLint("MissingPermission")
internal class AndroidBluetooth(private val context: Context, private val lost: (String) -> Unit) :
    BluetoothScanner, BluetoothConnector {
    private val adapter
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val enabled = MutableStateFlow(false)
    private val selected = ConcurrentHashMap<ScanResultId, String>()
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refresh()
            }
        }

    init {
        refresh()
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun refresh() {
        enabled.value =
            has(Manifest.permission.BLUETOOTH_CONNECT) &&
                try {
                    adapter?.isEnabled == true
                } catch (_: SecurityException) {
                    false
                }
    }

    fun permissionGranted() =
        has(Manifest.permission.BLUETOOTH_SCAN) && has(Manifest.permission.BLUETOOTH_CONNECT)

    private fun has(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun address(id: ScanResultId): String? = selected[id]

    override fun scan(profile: GattProfile): Flow<Advertisement> = callbackFlow {
        if (!has(Manifest.permission.BLUETOOTH_SCAN) || !has(Manifest.permission.BLUETOOTH_CONNECT))
            throw SecurityException("Bluetooth permission required")
        refresh()
        val scanner = adapter?.bluetoothLeScanner ?: throw IOException("Bluetooth unavailable")
        selected.clear()
        val ids = ConcurrentHashMap<String, ScanResultId>()
        val callback =
            object : ScanCallback() {
                override fun onScanFailed(errorCode: Int) {
                    close(IOException("Bluetooth discovery failed"))
                }

                override fun onScanResult(
                    callbackType: Int,
                    result: android.bluetooth.le.ScanResult,
                ) {
                    val name = result.scanRecord?.deviceName ?: return
                    val matches =
                        if (profile == GattProfile.Coyote) name == "47L121000"
                        else listOf("OHD", "Handy", "The Handy").any(name::startsWith)
                    if (!matches) return
                    val address = result.device.address
                    if (address !in ids.keys && ids.size >= 64) return
                    val id =
                        ids.computeIfAbsent(address) { ScanResultId(UUID.randomUUID().toString()) }
                    selected[id] = address
                    trySend(Advertisement(id, name, result.rssi))
                }
            }
        val filter =
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(profile.service)).build()
        scanner.startScan(
            listOf(filter),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback,
        )
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                refresh()
            }
        }
    }

    override suspend fun connect(
        id: ScanResultId,
        profile: GattProfile,
        timeout: Duration,
    ): BluetoothLink {
        if (!has(Manifest.permission.BLUETOOTH_CONNECT))
            throw SecurityException("Bluetooth permission required")
        val address = selected[id] ?: id.value
        if (!BluetoothAdapter.checkBluetoothAddress(address))
            throw IOException("Expired Bluetooth selection")
        val device = adapter?.getRemoteDevice(address) ?: throw IOException("Bluetooth unavailable")
        return AndroidGattLink(profile) { lost(address) }.connect(context, device, timeout)
    }
}

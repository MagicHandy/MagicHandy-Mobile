package com.magichandy.mobile.platform.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.magichandy.mobile.core.api.ScanResultId
import com.magichandy.mobile.transport.ble.Advertisement
import com.magichandy.mobile.transport.ble.BluetoothConnector
import com.magichandy.mobile.transport.ble.BluetoothScanner
import com.magichandy.mobile.transport.ble.GattProfile
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter

@SuppressLint("MissingPermission")
internal class AndroidBluetooth(private val context: Context) : BluetoothScanner {
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
                    val (name, address) =
                        try {
                            (result.scanRecord?.deviceName ?: result.device.name ?: return) to
                                result.device.address
                        } catch (denied: SecurityException) {
                            close(denied)
                            return
                        }
                    val matches =
                        if (profile == GattProfile.Coyote) name == "47L121000"
                        else
                            listOf("OHD", "Handy", "The Handy").any {
                                name.startsWith(it, ignoreCase = true)
                            }
                    if (!matches) return
                    if (address !in ids.keys && ids.size >= 64) return
                    val id =
                        ids.computeIfAbsent(address) { ScanResultId(UUID.randomUUID().toString()) }
                    selected[id] = address
                    trySend(Advertisement(id, name, result.rssi))
                }
            }
        val filter =
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(profile.service)).build()
        try {
            scanner.startScan(
                listOf(filter),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        } catch (_: IllegalStateException) {
            throw IOException("Bluetooth adapter became unavailable")
        }
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                refresh()
            } catch (_: IllegalStateException) {
                refresh()
            }
        }
    }

    fun connector(onLost: () -> Unit): BluetoothConnector =
        BluetoothConnector { id, profile, timeout ->
            if (!has(Manifest.permission.BLUETOOTH_CONNECT))
                throw SecurityException("Bluetooth permission required")
            val address = selected[id] ?: id.value
            if (!BluetoothAdapter.checkBluetoothAddress(address))
                throw IOException("Expired Bluetooth selection")
            val device =
                adapter?.getRemoteDevice(address) ?: throw IOException("Bluetooth unavailable")
            AndroidGattLink(profile, lost = onLost).connect(context, device, timeout)
        }
}

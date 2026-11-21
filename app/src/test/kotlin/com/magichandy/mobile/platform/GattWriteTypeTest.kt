package com.magichandy.mobile.platform

import android.bluetooth.BluetoothGattCharacteristic
import com.magichandy.mobile.platform.bluetooth.gattWriteType
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

internal class GattWriteTypeTest {
    @Test
    fun `write selection respects requested acknowledgement and advertised properties`() {
        val write = BluetoothGattCharacteristic.PROPERTY_WRITE
        val command = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        val reliable = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val immediate = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        assertEquals(reliable, gattWriteType(write or command, withResponse = true))
        assertEquals(immediate, gattWriteType(write or command, withResponse = false))
        assertEquals(reliable, gattWriteType(write, withResponse = false))
        assertEquals(immediate, gattWriteType(command, withResponse = true))
        assertFailsWith<IOException> {
            gattWriteType(BluetoothGattCharacteristic.PROPERTY_READ, withResponse = true)
        }
    }
}

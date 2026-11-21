package com.magichandy.mobile.ui.setup

import com.magichandy.mobile.R
import com.magichandy.mobile.ui.devices.AddDeviceOrigin
import com.magichandy.mobile.ui.devices.addDeviceReturnResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
internal class SetupResourcesTest {
    @Test
    fun addDeviceReachedFromSetupUsesTruthfulReturnLabels() {
        val resources = RuntimeEnvironment.getApplication().resources
        val setup = addDeviceReturnResources(AddDeviceOrigin.Setup)
        val setupBreadcrumb = resources.getString(setup.breadcrumb)
        val setupBack =
            resources.getString(
                R.string.back_to_page,
                resources.getString(requireNotNull(setup.backTo)),
            )
        assertEquals("Setup › Add", setupBreadcrumb)
        assertEquals("Back to Setup", setupBack)
        assertFalse(setupBreadcrumb.contains("Devices"))
        assertFalse(setupBack.contains("Devices"))

        val devices = addDeviceReturnResources(AddDeviceOrigin.Devices)
        assertEquals("Devices › Add", resources.getString(devices.breadcrumb))
        assertEquals("Back to Devices", resources.getString(R.string.back_to_devices))
    }
}

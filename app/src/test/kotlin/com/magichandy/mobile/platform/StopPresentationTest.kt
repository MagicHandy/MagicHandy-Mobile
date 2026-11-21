package com.magichandy.mobile.platform

import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStopOutcome
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopReceipt
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.ui.stop.dismissalKey
import com.magichandy.mobile.ui.stop.isDismissed
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SyntheticApp::class)
internal class StopPresentationTest {
    @Test
    fun `a dismissal belongs to just one Stop episode`() {
        val app = RuntimeEnvironment.getApplication() as SyntheticApp
        val state = app.core.state.value
        val row = state.devices.devices.first()
        val outcome =
            DeviceStopOutcome(
                row.id,
                row.name,
                row.family,
                row.connection,
                StopOutcome.Confirmed,
                0.seconds,
                null,
                row.afterLoss,
            )
        val first = StopReceipt(1, Instant.fromEpochMilliseconds(0), listOf(outcome), false, false)
        val next = first.copy(at = first.at + 1.minutes)
        assertFalse(isDismissed(next, dismissalKey(first)))
        assertFalse(isDismissed(first.copy(episodeId = 1), dismissalKey(first)))
    }

    @Test
    fun `a due check-in changes the private notification text`() {
        val app = RuntimeEnvironment.getApplication() as SyntheticApp
        val state = app.core.state.value
        val notice = CoreNotice(app)
        val due =
            state.copy(banners = listOf(Banner.CheckInDue(DeviceId("v"), "VacuGlide", 1.minutes)))
        assertNotEquals(notice.privateText(state), notice.privateText(due))
    }

    @Test
    fun `tearing down a promoted foreground service directly stops its core`() {
        val app = RuntimeEnvironment.getApplication() as SyntheticApp
        val controller = Robolectric.buildService(CoreService::class.java).create()
        app.host.finished(true)
        controller.destroy()
        assertEquals(listOf(StopSource.ServiceLost), app.stops)
    }
}

package com.magichandy.mobile.platform

import android.app.*
import android.content.Intent
import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.createMagicHandyCore
import com.magichandy.mobile.transport.simulated.simulatedDependencies
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

internal class SyntheticApp : Application(), CoreOwner {
    private val fake by lazy {
        createMagicHandyCore(
            simulatedDependencies(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        )
    }
    val stops = mutableListOf<StopSource>()
    override val core by lazy {
        object : MagicHandyCore by fake {
            override fun emergencyStop(source: StopSource) {
                stops += source
            }
        }
    }
    override val host by lazy { AndroidHost(this) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SyntheticApp::class)
internal class ServiceTest {
    private val app: SyntheticApp
        get() = RuntimeEnvironment.getApplication() as SyntheticApp

    @Test
    fun publicNotificationCarriesOnlyActiveAndExplicitStop() {
        val notice = CoreNotice(app).build(app.core.state.value)
        val public = notice.publicVersion
        assertEquals(
            "MagicHandy · Active",
            public.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertFalse(public.extras.containsKey(Notification.EXTRA_TEXT))
        assertEquals(listOf("Stop"), public.actions.map { it.title })
        val intent = Shadows.shadowOf(public.actions.single().actionIntent).savedIntent
        assertEquals(StopActionReceiver::class.java.name, intent.component?.className)
        StopActionReceiver().onReceive(app, intent)
        assertEquals(listOf(StopSource.Notification), app.stops)
        assertEquals(CoreNotice.openLive, Shadows.shadowOf(notice.contentIntent).savedIntent.action)
    }

    @Test
    fun swipeAwayStopsImmediatelyAndBackgroundStartIsRefused() = runTest {
        val service = Robolectric.buildService(CoreService::class.java).create()
        assertFalse(app.host.ensureRunning())
        service.get().onTaskRemoved(Intent())
        assertEquals(listOf(StopSource.TaskRemoved), app.stops)
        service.destroy()
    }

    @Test
    fun tileStopsWithoutOpeningAnActivity() {
        val service = Robolectric.buildService(StopTileService::class.java).create()
        service.get().onClick()
        assertEquals(listOf(StopSource.QuickSettingsTile), app.stops)
    }

    @Test
    fun browserHasNoListenerBeforeSignInAndClosesItsReservedLoopbackPort() {
        val browser =
            LoopbackBrowser(CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
                error("unexpected browser launch")
            }
        val uri = java.net.URI(browser.redirectUri)
        assertEquals("127.0.0.1", uri.host)
        assertEquals("/auth/callback", uri.path)
        browser.close()
        java.net.ServerSocket(uri.port, 1, java.net.InetAddress.getByName("127.0.0.1")).use {
            assertTrue(it.isBound)
        }
    }
}

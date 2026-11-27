package com.magichandy.mobile.platform

import android.app.Application
import android.app.Notification
import android.content.Intent
import android.os.Looper
import com.magichandy.mobile.core.api.AutopilotStatus
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.createMagicHandyCore
import com.magichandy.mobile.transport.simulated.simulatedDependencies
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

internal class SyntheticApp : Application(), CoreOwner {
    private val fake by lazy {
        createMagicHandyCore(
            simulatedDependencies(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        )
    }
    override val stopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tileEvents = mutableListOf<Boolean>()
    val stops = mutableListOf<StopSource>()
    var stopBarrier: CompletableDeferred<Unit>? = null
    override val core by lazy {
        object : MagicHandyCore by fake {
            override fun updateStopTileAdded(added: Boolean) {
                tileEvents += added
            }

            override suspend fun awaitEmergencyStop() {
                stopBarrier?.await()
            }

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
    fun aPendingActivePlanKeepsTheCpuAwakeAndAStationaryHeldPlanReleasesIt() {
        val state = app.core.state.value
        for (status in AutopilotStatus.entries) {
            val view =
                serviceView(state.copy(autopilot = state.autopilot.copy(status = status)), "")
            assertEquals(
                status == AutopilotStatus.Active,
                view.needsWake,
            )
        }
    }

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
    fun tileReportsAdditionListeningAndRemoval() {
        val service = Robolectric.buildService(StopTileService::class.java).create().get()
        service.onTileAdded()
        service.onStartListening()
        service.onTileRemoved()
        assertEquals(listOf(true, true, false), app.tileEvents)
        assertTrue(app.stops.isEmpty())
    }

    @Test
    fun allowingNotificationsRepostsTheOngoingNotification() {
        // Android hides a notification posted before the person allows them, and nothing else
        // about the service changes when they do, so the permission itself must count as a change.
        val state = app.core.state.value
        val refused = state.copy(app = state.app.copy(notificationsAllowed = false))
        val allowed = state.copy(app = state.app.copy(notificationsAllowed = true))
        assertTrue(serviceView(refused, "Vibrator") != serviceView(allowed, "Vibrator"))
        assertEquals(serviceView(allowed, "Vibrator"), serviceView(allowed, "Vibrator"))
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
            LoopbackBrowser(
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                {},
                { "Return" },
            ) { _, _ ->
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

    @Test
    fun `task removal fences immediately and retains the service through Stop delivery`() {
        val controller = Robolectric.buildService(CoreService::class.java).create()
        val service = controller.get()
        val main = Shadows.shadowOf(Looper.getMainLooper())
        val barrier = CompletableDeferred<Unit>()
        app.stopBarrier = barrier
        service.onTaskRemoved(Intent())
        main.idle()
        assertEquals(listOf(StopSource.TaskRemoved), app.stops)
        assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
        barrier.complete(Unit)
        main.idle()
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }
}

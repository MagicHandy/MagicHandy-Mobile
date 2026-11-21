package com.magichandy.mobile.platform

import com.magichandy.mobile.MagicHandyApp
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.UserIntent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MagicHandyApp::class)
internal class ReleaseGraphTest {
    @org.junit.Test
    fun emptyProductionGraphStartsIdleAndCommitsThroughRoom() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as MagicHandyApp
        val core = platformCore(app)
        try {
            core.dispatch(UserIntent.AcknowledgeAdultsOnly)
            val s = withTimeout(5000) { core.state.first { it.firstRun.hasAcknowledgedAdultsOnly } }
            assertEquals(RunPhase.Idle, s.run.phase)
            assertTrue(s.devices.devices.isEmpty())
            assertTrue(s.models.connections.isEmpty())
            assertFalse(s.settings.isMemoryOn)
        } finally {
            app.processScope.cancel()
        }
    }
}

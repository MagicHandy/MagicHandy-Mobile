package com.magichandy.mobile.platform

import com.magichandy.mobile.MagicHandyApp
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.activePersona
import com.magichandy.mobile.platform.storage.PlatformDatabase
import com.magichandy.mobile.platform.storage.RoomStores
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MagicHandyApp::class)
internal class DebugPersonaGraphTest {
    @Test
    fun androidDebugKeepsSetupAndLabelledCannedRepliesInItsOwnDatabase() =
        runBlocking(Dispatchers.IO) {
            val app = RuntimeEnvironment.getApplication() as MagicHandyApp
            try {
                val core = app.core
                core.dispatch(UserIntent.AcknowledgeAdultsOnly)
                withTimeout(5000) { core.state.first { it.firstRun.hasAcknowledgedAdultsOnly } }
                core.dispatch(UserIntent.FinishSetup)
                withTimeout(5000) { core.state.first { it.firstRun.isSetupDone } }
                val voice = Personality(Explicitness.Warm)
                assertEquals(
                    FormResult.Accepted,
                    core.submit(UserIntent.SavePersona(null, "Kit", voice, use = true)),
                )
                core.dispatch(UserIntent.SendMessage("A synthetic turn"))
                withTimeout(5000) {
                    core.state.first {
                        it.chat.entries.filterIsInstance<ChatEntry.Message>().any { m ->
                            m.speaker == "Kit"
                        }
                    }
                }
                val db = PlatformDatabase.open(app, "simulated.db")
                try {
                    val stored = RoomStores(db, app.processScope)
                    stored.awaitReady()
                    val prefs = stored.settings.state.value
                    assertTrue(prefs.values.isSetupDone)
                    assertEquals("Kit", prefs.activePersona.name)
                    assertEquals(voice, prefs.activePersona.personality)
                    val message =
                        stored.chats.state.value.chats.values.single().messages.last()
                            as ChatEntry.Message
                    assertEquals("Kit", message.speaker)
                    assertTrue(message.text.startsWith("(Kit · Warm) "))
                } finally {
                    db.close()
                }
            } finally {
                app.processScope.cancel()
            }
        }
}

package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.InterruptCause
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.api.VoiceModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * What voice input relies on from the core: a Stop it can see, and replies kept while it listens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class VoiceInputTest {
    @Test
    fun `every Stop advances the stop epoch voice input watches`() = runTest {
        val f = DebugFixture(this)
        f.onboard()
        val start = f.core.state.value.stopEpoch
        f.core.emergencyStop(StopSource.SpokenStop)
        runCurrent()
        val afterSpoken = f.core.state.value.stopEpoch
        assertTrue(afterSpoken > start)
        f.connect(DeviceId("handy"))
        f.send(UserIntent.StartMotion)
        f.core.emergencyStop(StopSource.AppFooter)
        runCurrent()
        assertTrue(f.core.state.value.stopEpoch > afterSpoken)
        f.scope.cancel()
    }

    @Test
    fun `a reply keeps streaming in the background while voice input listens`() = runTest {
        val f = DebugFixture(this)
        f.onboard()
        f.core.setVoiceActive(true)
        f.send(UserIntent.SendMessage("A synthetic turn."))
        f.core.appBackgrounded()
        runCurrent()
        assertTrue(f.core.state.value.chat.reply !is ReplyState.Interrupted)
        f.scope.cancel()
    }

    @Test
    fun `the chosen speech model is saved with the settings`() = runTest {
        val f = DebugFixture(this)
        f.onboard()
        assertEquals(VoiceModel.Compact, f.core.state.value.app.voiceModel)
        f.send(UserIntent.SetVoiceModel(VoiceModel.Full))
        runCurrent()
        assertEquals(VoiceModel.Full, f.core.state.value.app.voiceModel)
        f.send(UserIntent.SetVoiceModel(VoiceModel.Compact))
        runCurrent()
        assertEquals(VoiceModel.Compact, f.core.state.value.app.voiceModel)
        f.scope.cancel()
    }

    @Test
    fun `without voice input a reply still stops when the app goes to the background`() = runTest {
        val f = DebugFixture(this)
        f.onboard()
        f.send(UserIntent.SendMessage("A synthetic turn."))
        f.core.appBackgrounded()
        runCurrent()
        assertEquals(
            ReplyState.Interrupted(InterruptCause.AppInBackground),
            f.core.state.value.chat.reply,
        )
        f.scope.cancel()
    }
}

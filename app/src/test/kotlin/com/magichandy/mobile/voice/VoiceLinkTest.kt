package com.magichandy.mobile.voice

import android.app.Application
import android.content.Intent
import com.magichandy.mobile.core.api.StopSource
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What crosses from the voice process to the main process, and back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
internal class VoiceLinkTest {
    @Test
    fun `a spoken Stop stops every device before voice input hears of it`() {
        val events = mutableListOf<String>()
        VoiceLinkService.route(
            VoiceProtocol.message(VoiceProtocol.SPOKEN_STOP, session = 3),
            stop = { events += "stop:" + it.name },
            forward = { events += "voice:" + it.what },
        )
        assertEquals(
            listOf("stop:" + StopSource.SpokenStop.name, "voice:" + VoiceProtocol.SPOKEN_STOP),
            events,
        )
    }

    @Test
    fun `a stale session's spoken Stop still stops`() {
        val stops = mutableListOf<StopSource>()
        VoiceLinkService.route(
            VoiceProtocol.message(VoiceProtocol.SPOKEN_STOP, session = 0),
            stop = { stops += it },
            forward = {},
        )
        assertEquals(listOf(StopSource.SpokenStop), stops)
    }

    @Test
    fun `turns and the meter never stop anything`() {
        val stops = mutableListOf<StopSource>()
        val forwarded = mutableListOf<Int>()
        for (message in
            listOf(
                VoiceProtocol.turn(1, "Go a little faster."),
                VoiceProtocol.level(1, 0.5f, isHearing = true, readout = null),
                VoiceProtocol.ended(1, VoiceEnd.User),
            )) {
            VoiceLinkService.route(
                message,
                stop = { stops += it },
                forward = { forwarded += it.what },
            )
        }
        assertEquals(emptyList(), stops)
        assertEquals(
            listOf(VoiceProtocol.TURN, VoiceProtocol.LEVEL, VoiceProtocol.ENDED),
            forwarded,
        )
    }

    @Test
    fun `an ended session names its reason`() {
        assertEquals(
            VoiceEnd.MicTaken,
            VoiceProtocol.reason(VoiceProtocol.ended(2, VoiceEnd.MicTaken)),
        )
        // An unknown reason, as from a newer voice process, reads as a failure.
        assertEquals(
            VoiceEnd.ModelFailed,
            VoiceProtocol.reason(VoiceProtocol.message(VoiceProtocol.ENDED, 2, arg2 = 99)),
        )
    }

    @Test
    fun `the settings reach the voice process as they were chosen`() {
        val config =
            VoiceConfig(
                source = MicSource.Raw,
                raise = false,
                detector = Detector.Ten,
                threshold = 0.3f,
                silenceSeconds = 1.5f,
                showLevels = true,
            )
        assertEquals(config, VoiceConfig.from(config.into(Intent())))
    }

    @Test
    fun `missing or unknown settings fall back to the tested defaults`() {
        assertEquals(VoiceConfig(), VoiceConfig.from(Intent()))
        val odd =
            Intent()
                .putExtra("voice.source", "Bluetooth")
                .putExtra("voice.threshold", 0.05f)
                .putExtra("voice.silence", 9f)
        assertEquals(VoiceConfig(), VoiceConfig.from(odd))
    }
}

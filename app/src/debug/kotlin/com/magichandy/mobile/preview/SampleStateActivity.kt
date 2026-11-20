package com.magichandy.mobile.preview

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ScanResult
import com.magichandy.mobile.core.api.ScanResultId
import com.magichandy.mobile.core.api.ScanResultStatus
import com.magichandy.mobile.core.api.ScanState
import com.magichandy.mobile.core.api.ServerCheck
import com.magichandy.mobile.ui.AppContent
import com.magichandy.mobile.ui.preview.SampleStates
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import kotlin.time.Duration.Companion.milliseconds

/**
 * Debug only: shows the real UI over a fixed sample state, so each screen can be compared with its
 * design board. Intents do nothing; navigation works.
 *
 * `adb shell am start -n com.magichandy.mobile/.preview.SampleStateActivity --es state playing --es
 * palette Lowlight`
 */
class SampleStateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val base = sample(intent.getStringExtra(EXTRA_STATE))
        val palette = intent.getStringExtra(EXTRA_PALETTE)?.let(Palette::valueOf)
        val state = if (palette == null) base else base.copy(app = base.app.copy(palette = palette))
        setContent {
            MagicHandyTheme(state.app.palette) { AppContent(state, onIntent = {}, onStop = {}) }
        }
    }

    private fun sample(name: String?): AppState =
        when (name) {
            "first" -> SampleStates.firstRun
            "stopped" -> SampleStates.stopped
            "lost" -> SampleStates.connectionLost
            "interrupted" -> SampleStates.interrupted
            "scan" -> scanning()
            "coyote" -> coyoteUnread()
            "server" -> serverFound()
            else -> SampleStates.playing
        }

    private fun scanning(): AppState {
        val playing = SampleStates.playing
        val results =
            listOf(
                ScanResult(ScanResultId("near"), "The Handy", -48, ScanResultStatus.Verifying),
                ScanResult(ScanResultId("far"), "The Handy", -77, ScanResultStatus.Found),
            )
        return playing.copy(
            devices = playing.devices.copy(scan = ScanState.Scanning(DeviceFamily.Handy, results))
        )
    }

    private fun coyoteUnread(): AppState {
        val playing = SampleStates.playing
        return playing.copy(devices = playing.devices.copy(hasAcknowledgedCoyoteNotes = false))
    }

    private fun serverFound(): AppState {
        val playing = SampleStates.playing
        val found =
            ServerCheck.Found(
                address = "192.168.0.20:11434",
                provider = ProviderKind.Ollama,
                isEncrypted = false,
                models = listOf("gemma4:12b", "gemma4:e4b"),
                selectedModel = "gemma4:12b",
                suggested = CapabilityLevel.Intermediate,
                testedAt = CapabilityLevel.Intermediate,
                contextNeeded = 32_768,
                contextAllowed = 32_768,
                repliedIn = 1_900.milliseconds,
            )
        return playing.copy(models = playing.models.copy(serverCheck = found))
    }

    private companion object {
        const val EXTRA_STATE = "state"
        const val EXTRA_PALETTE = "palette"
    }
}

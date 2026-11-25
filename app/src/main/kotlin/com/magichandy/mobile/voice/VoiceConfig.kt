package com.magichandy.mobile.voice

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.magichandy.mobile.R

/**
 * How voice input listens (docs/voice-design.md §5.2). Releases always use the defaults, which the
 * voice test settled; test builds let the Advanced section of Settings › Voice change them. The
 * spoken-Stop checks keep their own fixed values whatever these say ([SpokenStopWatch]).
 *
 * @property source the microphone path. While TalkBack is on, listening uses [MicSource.EchoCancel]
 *   instead, whatever this says.
 * @property raise whether [AutoLevel] raises quiet speech.
 * @property threshold the speech detector's confidence where a turn's speech starts.
 * @property silenceSeconds the pause that ends a turn.
 * @property showLevels whether the voice bar shows the microphone's level, the noise floor, the
 *   gain and the detector's confidence.
 */
data class VoiceConfig(
    val source: MicSource = MicSource.Standard,
    val raise: Boolean = true,
    val detector: Detector = Detector.Silero,
    val threshold: Float = DEFAULT_THRESHOLD,
    val silenceSeconds: Float = DEFAULT_SILENCE_SECONDS,
    val showLevels: Boolean = false,
) {
    /** Adds this to [intent], for the voice process. */
    fun into(intent: Intent): Intent =
        intent
            .putExtra(SOURCE, source.name)
            .putExtra(RAISE, raise)
            .putExtra(DETECTOR, detector.name)
            .putExtra(THRESHOLD, threshold)
            .putExtra(SILENCE, silenceSeconds)
            .putExtra(SHOW_LEVELS, showLevels)

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f
        const val DEFAULT_SILENCE_SECONDS = 0.6f

        /** The choices for where speech starts. */
        val thresholds = listOf(0.3f, 0.4f, 0.5f, 0.6f)

        /** The choices for the silence that ends a turn, in seconds. */
        val silences = listOf(0.4f, 0.6f, 0.8f, 1.0f, 1.5f)

        private const val SOURCE = "voice.source"
        private const val RAISE = "voice.raise"
        private const val DETECTOR = "voice.detector"
        private const val THRESHOLD = "voice.threshold"
        private const val SILENCE = "voice.silence"
        private const val SHOW_LEVELS = "voice.levels"

        /** The settings [into] added to [intent], with the defaults for anything missing. */
        fun from(intent: Intent): VoiceConfig {
            val defaults = VoiceConfig()
            return VoiceConfig(
                source =
                    MicSource.entries.firstOrNull { it.name == intent.getStringExtra(SOURCE) }
                        ?: defaults.source,
                raise = intent.getBooleanExtra(RAISE, defaults.raise),
                detector =
                    Detector.entries.firstOrNull { it.name == intent.getStringExtra(DETECTOR) }
                        ?: defaults.detector,
                threshold =
                    intent.getFloatExtra(THRESHOLD, defaults.threshold).takeIf { it in thresholds }
                        ?: defaults.threshold,
                silenceSeconds =
                    intent.getFloatExtra(SILENCE, defaults.silenceSeconds).takeIf {
                        it in silences
                    } ?: defaults.silenceSeconds,
                showLevels = intent.getBooleanExtra(SHOW_LEVELS, defaults.showLevels),
            )
        }
    }
}

/**
 * Where test builds keep the Advanced section's choices: apart from the app's saved settings, as
 * the voice test keeps its own (docs/voice-design.md §5.2). Release builds never read or write it,
 * and always listen with the defaults.
 */
internal class VoiceTuning(context: Context) {
    /** True in debug and phone-test builds, which show the hidden settings. */
    val isShown: Boolean = context.resources.getBoolean(R.bool.voice_hidden_settings)

    private val prefs by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    /** The test build's choices, or the defaults in a release. */
    fun read(): VoiceConfig {
        if (!isShown) return VoiceConfig()
        val defaults = VoiceConfig()
        return VoiceConfig(
            source =
                MicSource.entries.firstOrNull { it.name == prefs.getString(SOURCE, null) }
                    ?: defaults.source,
            raise = prefs.getBoolean(RAISE, defaults.raise),
            detector =
                Detector.entries.firstOrNull { it.name == prefs.getString(DETECTOR, null) }
                    ?: defaults.detector,
            threshold =
                prefs.getFloat(THRESHOLD, defaults.threshold).takeIf {
                    it in VoiceConfig.thresholds
                } ?: defaults.threshold,
            silenceSeconds =
                prefs.getFloat(SILENCE, defaults.silenceSeconds).takeIf {
                    it in VoiceConfig.silences
                } ?: defaults.silenceSeconds,
            showLevels = prefs.getBoolean(SHOW_LEVELS, defaults.showLevels),
        )
    }

    fun write(config: VoiceConfig) {
        if (!isShown) return
        prefs.edit {
            putString(SOURCE, config.source.name)
            putBoolean(RAISE, config.raise)
            putString(DETECTOR, config.detector.name)
            putFloat(THRESHOLD, config.threshold)
            putFloat(SILENCE, config.silenceSeconds)
            putBoolean(SHOW_LEVELS, config.showLevels)
        }
    }

    private companion object {
        const val FILE = "voice-advanced"
        const val SOURCE = "source"
        const val RAISE = "raise"
        const val DETECTOR = "detector"
        const val THRESHOLD = "threshold"
        const val SILENCE = "silence"
        const val SHOW_LEVELS = "levels"
    }
}

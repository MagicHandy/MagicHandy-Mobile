package com.magichandy.mobile.voice

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.Detector
import com.magichandy.mobile.core.api.MicSource
import com.magichandy.mobile.core.api.VoiceConfig

/** Adds this to [intent], for the voice process. */
fun VoiceConfig.into(intent: Intent): Intent =
    intent
        .putExtra(SOURCE, source.name)
        .putExtra(RAISE, raise)
        .putExtra(DETECTOR, detector.name)
        .putExtra(THRESHOLD, threshold)
        .putExtra(SILENCE, silenceSeconds)
        .putExtra(SHOW_LEVELS, showLevels)

private const val SOURCE = "voice.source"
private const val RAISE = "voice.raise"
private const val DETECTOR = "voice.detector"
private const val THRESHOLD = "voice.threshold"
private const val SILENCE = "voice.silence"
private const val SHOW_LEVELS = "voice.levels"

/** The settings [into] added to [intent], with the defaults for anything missing. */
fun VoiceConfig.Companion.from(intent: Intent): VoiceConfig {
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

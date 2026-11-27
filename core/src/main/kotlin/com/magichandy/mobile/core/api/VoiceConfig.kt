package com.magichandy.mobile.core.api

/** Microphone processing requested from Android. */
public enum class MicSource {
    Standard,
    Speech,
    EchoCancel,
    Raw,
}

/** On-device speech detector selection. */
public enum class Detector {
    Silero,
    Ten,
}

/**
 * How voice input listens (docs/voice-design.md §5.2). Releases always use the defaults, which the
 * voice test settled; test builds let the Advanced section of Settings › Voice change them. The
 * spoken-Stop checks keep their own fixed values whatever these say in the recognizer.
 *
 * @property source the microphone path. While TalkBack is on, listening uses [MicSource.EchoCancel]
 *   instead, whatever this says.
 * @property raise whether to raise quiet speech before detection and transcription.
 * @property threshold the speech detector's confidence where a turn's speech starts.
 * @property silenceSeconds the pause that ends a turn.
 * @property showLevels whether the voice bar shows the microphone's level, the noise floor, the
 *   gain and the detector's confidence.
 */
public data class VoiceConfig(
    public val source: MicSource = MicSource.Standard,
    public val raise: Boolean = true,
    public val detector: Detector = Detector.Silero,
    public val threshold: Float = DEFAULT_THRESHOLD,
    public val silenceSeconds: Float = DEFAULT_SILENCE_SECONDS,
    public val showLevels: Boolean = false,
) {
    public companion object {
        public const val DEFAULT_THRESHOLD: Float = 0.5f
        public const val DEFAULT_SILENCE_SECONDS: Float = 0.6f

        /** The choices for where speech starts. */
        public val thresholds: List<Float> = listOf(0.3f, 0.4f, 0.5f, 0.6f)

        /** The choices for the silence that ends a turn, in seconds. */
        public val silences: List<Float> = listOf(0.4f, 0.6f, 0.8f, 1.0f, 1.5f)
    }
}

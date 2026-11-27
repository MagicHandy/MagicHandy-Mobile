package com.magichandy.mobile.core.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything voice input shows in Chat and Settings (docs/voice-design.md §4, §5), as one immutable
 * snapshot. The meter moves faster, so it has a flow of its own ([VoiceControls.meter]).
 *
 * @property isOffered whether the phone reports enough memory for voice input at all.
 * @property canChooseModel whether it reports enough for full precision too, so the picker is
 *   offered.
 * @property installed the model on the phone that listening uses; null when none is.
 * @property chosen the model chosen in Settings. While it differs from [installed], voice is
 *   switching to it.
 * @property download the model download, when one is running, paused or failed.
 * @property listening set while listening, from the tap until it ends.
 * @property notice why listening or a start ended, until dismissed or listening starts again.
 * @property endedByStop the Stop epoch whose Stop ended listening, so the Stop receipt can say so.
 * @property showsHiddenSettings true in test builds, which show the Advanced section.
 * @property config the test build's Advanced choices; a release always listens with the defaults.
 */
public data class VoiceState(
    public val isOffered: Boolean = false,
    public val canChooseModel: Boolean = false,
    public val installed: VoiceModel? = null,
    public val chosen: VoiceModel = VoiceModel.Compact,
    public val download: VoiceDownload? = null,
    public val listening: VoiceListening? = null,
    public val notice: VoiceNotice? = null,
    public val endedByStop: Long? = null,
    public val showsHiddenSettings: Boolean = false,
    public val config: VoiceConfig = VoiceConfig(),
    /** Download and free-space requirements from the installed app's model catalog. */
    public val storage: Map<VoiceModel, VoiceModelStorage> = emptyMap(),
)

/**
 * A model download.
 *
 * @property startsListening true when it's the first download, from Download and start, and the app
 *   has stayed on screen: listening then starts when it finishes.
 */
public data class VoiceDownload(
    public val model: VoiceModel,
    public val done: Long,
    public val total: Long,
    public val phase: DownloadPhase,
    public val problem: DownloadProblem? = null,
    public val startsListening: Boolean = false,
) {
    /** From 0 to 1. */
    public val fraction: Float
        get() = if (total <= 0) 0f else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
}

public enum class DownloadPhase {
    Running,

    /** Waiting for the app to come back on screen, where it resumes. */
    Paused,
    Failed,
}

/** Why a download stopped, for its message. */
public sealed interface DownloadProblem {
    public data object NoConnection : DownloadProblem

    /**
     * Not enough space: [neededBytes] counts the download and the 500 MB it leaves free. [removing]
     * names the model whose removal would make room, when that would.
     */
    public data class NotEnoughSpace(val neededBytes: Long, val removing: VoiceModel?) :
        DownloadProblem

    /** A file didn't match its checksum. */
    public data object Damaged : DownloadProblem

    public data object Failed : DownloadProblem
}

/**
 * Listening, from the tap until it ends.
 *
 * @property isReady false while the model loads (2–5 s), when a spoken Stop isn't heard yet.
 * @property isWaiting true while a turn waits for the reply that's streaming.
 * @property cantSend why the latest turn couldn't be sent, until chat can send again.
 */
public data class VoiceListening(
    public val model: VoiceModel,
    public val isReady: Boolean = false,
    public val isWaiting: Boolean = false,
    public val cantSend: SendBlock? = null,
)

/** Why listening ended, or couldn't start, with the next step the notice offers. */
public data class VoiceNotice(public val reason: VoiceEnd, public val model: VoiceModel?)

/**
 * The voice bar's meter.
 *
 * @property levels the latest chunks' loudness from 0 to 1, oldest first.
 * @property readout the test builds' levels line, when Show levels is on.
 */
public data class VoiceMeter(
    public val levels: List<Float> = emptyList(),
    public val isHearing: Boolean = false,
    public val readout: String? = null,
)

/** What the UI can ask of voice input, and the state it shows. The UI's only view of voice. */
public interface VoiceControls {
    /** The current authoritative Stop epoch, including Stops not yet projected to the UI. */
    public val stopEpoch: Long
        get() = 0L

    public val state: StateFlow<VoiceState>
    public val meter: StateFlow<VoiceMeter>

    /** Starts listening. The UI asks for the microphone first. */
    public fun start()

    /** Ends listening, as End does. */
    public fun end()

    /**
     * Downloads [model], or switches to it. With [andStart], listening starts when it's ready, if
     * the app is still on screen and the original start authorization is current.
     */
    public fun download(model: VoiceModel, andStart: Boolean)

    /** Cancels the download and deletes what it fetched. */
    public fun cancelDownload()

    /** Retry the current transfer while preserving its last explicit microphone choice. */
    public fun retryDownload()

    /** Ends listening, cancels any download and deletes every model. */
    public fun remove()

    public fun dismissNotice()

    /** The microphone permission was refused. */
    public fun microphoneRefused()

    /** Test builds: the Advanced section's choices, used from the next start. */
    public fun tune(config: VoiceConfig)

    /** Does nothing, for previews and tests. */
    public class Fixed(initial: VoiceState = VoiceState(), meter: VoiceMeter = VoiceMeter()) :
        VoiceControls {
        override val state: StateFlow<VoiceState> = MutableStateFlow(initial)
        override val meter: StateFlow<VoiceMeter> = MutableStateFlow(meter)

        override fun start(): Unit = Unit

        override fun end(): Unit = Unit

        override fun download(model: VoiceModel, andStart: Boolean): Unit = Unit

        override fun cancelDownload(): Unit = Unit

        override fun retryDownload(): Unit = Unit

        override fun remove(): Unit = Unit

        override fun dismissNotice(): Unit = Unit

        override fun microphoneRefused(): Unit = Unit

        override fun tune(config: VoiceConfig): Unit = Unit
    }
}

/** Space requirements for a complete speech model, in bytes. */
public data class VoiceModelStorage(
    public val downloadBytes: Long,
    public val requiredFreeBytes: Long,
)

/** Why listening ended. */
public enum class VoiceEnd {
    /** End was tapped: in Chat, on the chip, or in the notification. */
    User,

    /** A Stop, spoken or not. Nothing heard after it can reach chat. */
    Stop,

    /** 15 minutes without speech while nothing played. */
    Quiet,

    /** Android gave the microphone to a call or another app, or its privacy switch turned off. */
    MicTaken,

    /** Microphone access was removed, or Android refused to start listening. */
    MicDenied,

    /** Reading the microphone failed. */
    MicFailed,

    /** The speech model's files were missing or damaged. */
    ModelMissing,

    /** The speech model failed to load, or ran out of memory. */
    ModelFailed,

    /** The recognizer could not keep up with real-time input within its audio budget. */
    Overloaded,

    /** The active input was not the built-in microphone. */
    InputChanged,
}

/**
 * How loud speech reached the phone and how much it was raised.
 *
 * @property inputDb the speech's level from the microphone, in dBFS.
 * @property gainDb the gain applied before the detector and the recognizer heard it.
 */
public data class SpeechLevel(public val inputDb: Float, public val gainDb: Float)

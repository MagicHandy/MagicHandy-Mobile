package com.magichandy.mobile.voice

import com.magichandy.mobile.core.api.SendBlock
import com.magichandy.mobile.core.api.VoiceModel
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
data class VoiceState(
    val isOffered: Boolean = false,
    val canChooseModel: Boolean = false,
    val installed: VoiceModel? = null,
    val chosen: VoiceModel = VoiceModel.Compact,
    val download: VoiceDownload? = null,
    val listening: VoiceListening? = null,
    val notice: VoiceNotice? = null,
    val endedByStop: Long? = null,
    val showsHiddenSettings: Boolean = false,
    val config: VoiceConfig = VoiceConfig(),
)

/**
 * A model download.
 *
 * @property startsListening true when it's the first download, from Download and start, and the app
 *   has stayed on screen: listening then starts when it finishes.
 */
data class VoiceDownload(
    val model: VoiceModel,
    val done: Long,
    val total: Long,
    val phase: DownloadPhase,
    val problem: DownloadProblem? = null,
    val startsListening: Boolean = false,
) {
    /** From 0 to 1. */
    val fraction: Float
        get() = if (total <= 0) 0f else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
}

enum class DownloadPhase {
    Running,

    /** Waiting for the app to come back on screen, where it resumes. */
    Paused,
    Failed,
}

/** Why a download stopped, for its message. */
sealed interface DownloadProblem {
    data object NoConnection : DownloadProblem

    /**
     * Not enough space: [neededBytes] counts the download and the 500 MB it leaves free. [removing]
     * names the model whose removal would make room, when that would.
     */
    data class NotEnoughSpace(val neededBytes: Long, val removing: VoiceModel?) : DownloadProblem

    /** A file didn't match its checksum. */
    data object Damaged : DownloadProblem

    data object Failed : DownloadProblem
}

/**
 * Listening, from the tap until it ends.
 *
 * @property isReady false while the model loads (2–5 s), when a spoken Stop isn't heard yet.
 * @property isWaiting true while a turn waits for the reply that's streaming.
 * @property cantSend why the latest turn couldn't be sent, until chat can send again.
 */
data class VoiceListening(
    val model: VoiceModel,
    val isReady: Boolean = false,
    val isWaiting: Boolean = false,
    val cantSend: SendBlock? = null,
)

/** Why listening ended, or couldn't start, with the next step the notice offers. */
data class VoiceNotice(val reason: VoiceEnd, val model: VoiceModel?)

/**
 * The voice bar's meter.
 *
 * @property levels the latest chunks' loudness from 0 to 1, oldest first.
 * @property readout the test builds' levels line, when Show levels is on.
 */
data class VoiceMeter(
    val levels: List<Float> = emptyList(),
    val isHearing: Boolean = false,
    val readout: String? = null,
)

/** What the UI can ask of voice input, and the state it shows. The UI's only view of voice. */
interface VoiceControls {
    val state: StateFlow<VoiceState>
    val meter: StateFlow<VoiceMeter>

    /** Starts listening. The UI asks for the microphone first. */
    fun start()

    /** Ends listening, as End does. */
    fun end()

    /**
     * Downloads [model], or switches to it. With [andStart], listening starts when it's ready, if
     * the app is still on screen.
     */
    fun download(model: VoiceModel, andStart: Boolean)

    /** Cancels the download and deletes what it fetched. */
    fun cancelDownload()

    fun retryDownload()

    /** Ends listening, cancels any download and deletes every model. */
    fun remove()

    fun dismissNotice()

    /** The microphone permission was refused. */
    fun microphoneRefused()

    /** Test builds: the Advanced section's choices, used from the next start. */
    fun tune(config: VoiceConfig)

    /** Does nothing, for previews and tests. */
    class Fixed(initial: VoiceState = VoiceState(), meter: VoiceMeter = VoiceMeter()) :
        VoiceControls {
        override val state: StateFlow<VoiceState> = MutableStateFlow(initial)
        override val meter: StateFlow<VoiceMeter> = MutableStateFlow(meter)

        override fun start() = Unit

        override fun end() = Unit

        override fun download(model: VoiceModel, andStart: Boolean) = Unit

        override fun cancelDownload() = Unit

        override fun retryDownload() = Unit

        override fun remove() = Unit

        override fun dismissNotice() = Unit

        override fun microphoneRefused() = Unit

        override fun tune(config: VoiceConfig) = Unit
    }
}

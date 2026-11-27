package com.magichandy.mobile.voicelab

import com.magichandy.mobile.core.api.Detector
import com.magichandy.mobile.core.api.MicSource
import com.magichandy.mobile.core.api.SpeechLevel
import java.util.Locale

/** A Parakeet build's files on this phone. */
sealed interface Fetch {
    data object Missing : Fetch

    data class Running(val done: Long, val total: Long) : Fetch

    data object Ready : Fetch

    data class Failed(val reason: String) : Fetch
}

/** ML Kit's state for one mode, as Google reports it. */
sealed interface KitStatus {
    data object Checking : KitStatus

    data object Unavailable : KitStatus

    data object Downloadable : KitStatus

    /** [total] is null until Google says how much there is. */
    data class Downloading(val done: Long, val total: Long?) : KitStatus

    data object Ready : KitStatus

    data class Failed(val reason: String) : KitStatus
}

/** How far the speaker is from the phone, recorded with each result. */
enum class Distance(val title: String) {
    Near("30 cm"),
    Arm("1 m"),
    Far("2 m"),
}

enum class LabMode {
    /** Listening continuously; each turn ends after a pause. */
    HandsFree,

    /** One recording at a time, run through every checked engine. */
    Clip,
}

/**
 * The microphone settings, recorded with each result.
 *
 * @property raise whether [AutoLevel] raises quiet speech before the detector and the engines.
 * @property threshold the detector's probability at which speech starts; lower hears more.
 */
data class MicSettings(
    val source: MicSource = MicSource.Standard,
    val raise: Boolean = true,
    val distance: Distance = Distance.Arm,
    val deviceNearby: Boolean = false,
    val detector: Detector = Detector.Silero,
    val threshold: Float = 0.5f,
    val silenceSeconds: Float = 0.6f,
) {
    /** The settings in a result's heading, such as "1 m · device running · Standard audio". */
    val tags: String
        get() =
            listOfNotNull(
                    distance.title,
                    "device running".takeIf { deviceNearby },
                    source.title + " audio",
                )
                .joinToString(" · ")

    /** The detector and where speech starts, such as "Silero 0.5". */
    val detectorTag: String
        get() = detector.title + " " + "%.1f".format(Locale.ROOT, threshold)
}

/** One bar of a meter: a loudness from 0 to 1, drawn in the accent colour when [isHot]. */
data class MeterBar(val level: Float, val isHot: Boolean = false)

/**
 * What the microphone and the detector make of the room, under the hands-free meter.
 *
 * @property micDb the loudest the microphone was over the last moment, in dBFS, before any gain.
 * @property raises whether [AutoLevel] is raising quiet speech.
 * @property noiseDb the noise floor it has heard, or null before any sound or when it's off.
 * @property gainDb the gain applied, in dB.
 * @property probability the detector's latest verdict, from 0 to 1.
 */
data class Reading(
    val micDb: Float,
    val raises: Boolean,
    val noiseDb: Float?,
    val gainDb: Float,
    val probability: Float,
)

/**
 * Hands-free listening while it runs.
 *
 * @property since when listening started, on the elapsed-realtime clock.
 * @property seconds how long it has been listening, updated with the meter.
 * @property bars the most recent loudness after any gain, oldest first, hot while speech is heard.
 * @property partial ML Kit's text so far for the current turn.
 * @property engines the engines listening, such as "Parakeet int8 and ML Kit Basic".
 * @property reading the numbers under the meter, once there's audio.
 */
data class Listening(
    val since: Long,
    val seconds: Long = 0,
    val bars: List<MeterBar> = emptyList(),
    val speaking: Boolean = false,
    val partial: String? = null,
    val engines: String = "",
    val reading: Reading? = null,
)

/** Where a clip is: idle, recording or being transcribed. */
sealed interface ClipPhase {
    data object Idle : ClipPhase

    data class Recording(
        val since: Long,
        val seconds: Long = 0,
        val bars: List<MeterBar> = emptyList(),
    ) : ClipPhase

    data class Transcribing(val engine: String) : ClipPhase
}

/**
 * One engine's result for a turn.
 *
 * @property latencyMillis from the end of speech to the text, for hands-free turns.
 * @property processMillis how long the engine worked on the audio.
 * @property realTime [processMillis] over the audio's length; below 1 is faster than real time.
 * @property score against the line read out, for clips.
 * @property wouldStop true when the app's typed-Stop rule would stop motion on this text.
 */
data class EngineResult(
    val engine: String,
    val text: String? = null,
    val latencyMillis: Long? = null,
    val processMillis: Long? = null,
    val realTime: Float? = null,
    val score: WordScore? = null,
    val wouldStop: Boolean = false,
    val error: String? = null,
) {
    val isPending: Boolean
        get() = text == null && error == null
}

/** An entry in the results: a turn, or a note such as ML Kit stopping. */
sealed interface LabEntry {
    val id: Long

    /** Wall-clock time in milliseconds. */
    val at: Long
}

/**
 * One turn of speech and every engine's result for it.
 *
 * @property speechMillis how long the speech lasted.
 * @property tags the settings it was heard with, such as "1 m · Standard audio".
 * @property reference the line read out, for clips.
 * @property level how loud the speech arrived and the gain it was given.
 */
data class Turn(
    override val id: Long,
    override val at: Long,
    val speechMillis: Long,
    val tags: String,
    val reference: String?,
    val results: List<EngineResult>,
    val level: SpeechLevel? = null,
) : LabEntry

data class LabNote(override val id: Long, override val at: Long, val text: String) : LabEntry

/**
 * Battery, temperature and memory, sampled every few seconds. [batteryAtStart] and [minutes] count
 * from when hands-free listening started.
 */
data class Vitals(
    val battery: Int? = null,
    val batteryAtStart: Int? = null,
    val minutes: Long? = null,
    val thermal: String = "Normal",
    val memoryMb: Int? = null,
)

/** Everything the voice test page shows. */
data class LabState(
    val builds: Map<ParakeetBuild, Fetch> = ParakeetBuild.entries.associateWith { Fetch.Missing },
    val checkedBuilds: Set<ParakeetBuild> = setOf(ParakeetBuild.Int8),
    val kits: Map<KitMode, KitStatus> = KitMode.entries.associateWith { KitStatus.Checking },
    val checkedKits: Set<KitMode> = setOf(KitMode.Basic),
    val language: KitLanguage = KitLanguage.English,
    val mic: MicSettings = MicSettings(),
    val mode: LabMode = LabMode.HandsFree,
    val listening: Listening? = null,
    val clip: ClipPhase = ClipPhase.Idle,
    val line: Int = 0,
    val lineText: String = LabScript.lines.first(),
    val entries: List<LabEntry> = emptyList(),
    val vitals: Vitals = Vitals(),
    val busy: String? = null,
    val notice: String? = null,
) {
    /** True while the microphone or an engine is in use, when settings shouldn't change. */
    val isRunning: Boolean
        get() = listening != null || clip != ClipPhase.Idle || busy != null
}

/** What the page can ask of the voice test. */
interface LabActions {
    fun download(build: ParakeetBuild) {}

    fun cancelDownload(build: ParakeetBuild) {}

    fun delete(build: ParakeetBuild) {}

    fun toggle(build: ParakeetBuild) {}

    fun downloadKit(mode: KitMode) {}

    fun toggle(mode: KitMode) {}

    fun setLanguage(language: KitLanguage) {}

    fun setMic(mic: MicSettings) {}

    fun setMode(mode: LabMode) {}

    fun startListening() {}

    fun stopListening() {}

    fun startClip() {}

    fun finishClip() {}

    fun moveLine(step: Int) {}

    fun editLine(text: String) {}

    fun runSample() {}

    fun clear() {}

    fun share() {}

    fun dismissNotice() {}
}

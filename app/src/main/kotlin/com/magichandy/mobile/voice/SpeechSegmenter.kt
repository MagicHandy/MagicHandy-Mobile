package com.magichandy.mobile.voice

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.pow

/**
 * One turn of speech.
 *
 * @property start where the speech starts, in samples from the start of listening.
 * @property end the sample just after the speech ends.
 * @property samples what the recognizer hears: the speech, levelled, with a little before and
 *   after.
 * @property level how loud the speech arrived and the gain it was given.
 * @property isShort shorter than a word: checked for a spoken Stop only.
 */
class Utterance(
    val start: Long,
    val end: Long,
    val samples: FloatArray,
    val level: SpeechLevel,
    val isShort: Boolean = false,
)

/** Audio after [SpeechSegmenter.accept]: the levelled samples, and any turns that ended. */
class Heard(val levelled: FloatArray, val utterances: List<Utterance>)

/**
 * Splits microphone audio into turns. Each window is levelled by [AutoLevel] when [raise] is on,
 * judged by the neural speech detector, and kept for a while, so each turn reaches the recognizer
 * with [PRE_ROLL_SECONDS] before the detector heard speech and [POST_ROLL_SECONDS] after it ended:
 * detectors are a little late to hear soft first sounds and early to drop trailing ones. Not
 * thread-safe: feed it from one thread, the capture thread.
 */
class SpeechSegmenter(
    model: File,
    private val detector: Detector,
    private val threshold: Float,
    silenceSeconds: Float,
    raise: Boolean,
) {
    private val vad = Vad(config = vadConfig(model, detector))
    private val level = if (raise) AutoLevel() else null
    private val endpointer = Endpointer(threshold, silenceSeconds, detector.window)
    private val history = AudioHistory(HISTORY_SECONDS * SAMPLE_RATE)
    private val released = AtomicBoolean(false)
    private var pending = FloatArray(0)

    /** The current turn's speech windows: their energy from the microphone and their gain. */
    private var voicedEnergy = 0.0
    private var voicedGain = 0.0
    private var voicedWindows = 0

    /** The detector's probability for the latest window, from 0 to 1. */
    var probability = 0f
        private set

    /** The gain applied to the latest window, in dB. */
    val gainDb: Float
        get() = level?.gainDb ?: 0f

    /** Whether it raises quiet speech. */
    val raises: Boolean
        get() = level != null

    /** The noise floor heard so far, in dBFS, or null before any sound or when not raising. */
    val noiseDb: Float?
        get() = level?.noiseDb

    /** True while the detector hears speech, and through the pause before a turn ends. */
    val isSpeaking: Boolean
        get() = endpointer.isSpeaking

    /** How many samples it has judged since listening started. */
    val position: Long
        get() = history.size

    /**
     * Levels and judges [samples], returning the levelled audio and any turns that ended.
     * [onWindow] gets each window's probability once the window is judged and kept, as the
     * spoken-Stop checks need.
     */
    fun accept(samples: FloatArray, onWindow: (probability: Float) -> Unit = {}): Heard {
        check(!released.get()) { "The speech detector was released" }
        val buffered = if (pending.isEmpty()) samples else pending + samples
        val window = detector.window
        val windows = buffered.size / window
        val levelled = FloatArray(windows * window)
        val ended = mutableListOf<Utterance>()
        for (index in 0 until windows) {
            val frame = buffered.copyOfRange(index * window, (index + 1) * window)
            val inputDb = levelDb(frame)
            val gain = level?.apply(frame) ?: 0f
            val speech = vad.compute(frame)
            level?.update(inputDb, speech >= threshold, window.toFloat() / SAMPLE_RATE)
            history.add(frame)
            frame.copyInto(levelled, index * window)
            probability = speech
            if (speech >= threshold) {
                voicedEnergy += BASE.pow(inputDb / DB_PER_BEL.toDouble())
                voicedGain += gain
                voicedWindows++
            }
            val span = endpointer.accept(speech)
            if (span != null) ended += cut(span)
            if (span != null || !endpointer.isSpeaking) clearVoiced()
            onWindow(speech)
        }
        pending = buffered.copyOfRange(windows * window, buffered.size)
        return Heard(levelled, ended)
    }

    /** The levelled audio from [from] up to [to], or the part of it still held. */
    fun recent(from: Long, to: Long): FloatArray = history.slice(from, to)

    /** Ends any turn in progress, as when listening stops. */
    fun flush(): List<Utterance> {
        val span = endpointer.flush() ?: return emptyList()
        return listOf(cut(span)).also { clearVoiced() }
    }

    /** Frees the native detector. Safe to call twice. */
    fun release() {
        if (released.compareAndSet(false, true)) vad.release()
    }

    private fun cut(span: Span): Utterance {
        val inputDb =
            if (voicedWindows == 0) SILENCE_DB
            else (DB_PER_BEL * log10(voicedEnergy / voicedWindows)).toFloat()
        val gainDb = if (voicedWindows == 0) gainDb else (voicedGain / voicedWindows).toFloat()
        return Utterance(
            span.start,
            span.end,
            history.slice(
                span.start - samples(PRE_ROLL_SECONDS),
                span.end + samples(POST_ROLL_SECONDS),
            ),
            SpeechLevel(inputDb, gainDb),
            span.isShort,
        )
    }

    private fun clearVoiced() {
        voicedEnergy = 0.0
        voicedGain = 0.0
        voicedWindows = 0
    }

    private companion object {
        const val PRE_ROLL_SECONDS = 0.3f
        const val POST_ROLL_SECONDS = 0.2f

        /** Longer than the longest turn, with its padding. */
        const val HISTORY_SECONDS = 30
        const val BASE = 10.0
        const val DB_PER_BEL = 10

        fun samples(seconds: Float): Long = (seconds * SAMPLE_RATE).toLong()

        /**
         * The detector's model. Only its probability is used, through [Vad.compute], so the
         * thresholds here are sherpa-onnx's defaults and play no part.
         */
        fun vadConfig(model: File, detector: Detector): VadModelConfig =
            VadModelConfig(
                sileroVadModelConfig =
                    if (detector == Detector.Silero) {
                        SileroVadModelConfig(model = model.path, windowSize = detector.window)
                    } else {
                        SileroVadModelConfig()
                    },
                tenVadModelConfig =
                    if (detector == Detector.Ten) {
                        TenVadModelConfig(model = model.path, windowSize = detector.window)
                    } else {
                        TenVadModelConfig()
                    },
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
    }
}

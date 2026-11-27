package com.magichandy.mobile.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.magichandy.mobile.core.api.MicSource
import com.magichandy.mobile.core.api.VoiceConfig
import com.magichandy.mobile.core.api.VoiceEnd
import com.magichandy.mobile.core.api.VoiceModel
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One listening session in the voice process (docs/voice-design.md §6–§8): the microphone, the
 * speech detector, the spoken-Stop checks and Parakeet. Audio lives only in memory and is never
 * written anywhere.
 *
 * Three threads take part: [start]'s loader, which loads the models and starts recording; the
 * microphone's, which levels and judges the audio and queues what the recognizer should hear; and
 * the recognizer's, which alternates bounded chat slices and Stop checks. Each frees what it uses
 * when it ends.
 */
internal class VoiceSession(
    private val context: Context,
    private val store: ModelStore,
    private val model: VoiceModel,
    private val config: VoiceConfig,
    private val source: MicSource,
    private val events: Events,
) {
    /** What the session tells the main process. Called from the session's own threads. */
    interface Events {
        fun listening()

        fun level(loudness: Float, isHearing: Boolean, readout: String?)

        fun spokenStop(lastSpeechElapsedMillis: Long)

        fun turn(text: String)

        /** Called once, when the session ends for [reason]. */
        fun ended(reason: VoiceEnd)
    }

    private val isEnded = AtomicBoolean(false)
    private val queue = RecognizerQueue()
    private val lock = Any()
    private var capture: MicCapture? = null
    @Volatile private var firstCaptureSample = -1L
    private var lastSpeechSample = 0L
    private var loader: Thread? = null
    private var recognizer: Thread? = null

    /**
     * Loads the models and starts listening on a thread of its own, once [after], the session
     * before it, has freed its models: two copies of the full model wouldn't fit in memory.
     */
    fun start(after: VoiceSession? = null) {
        synchronized(lock) {
            loader =
                thread(name = "voice-load") {
                    after?.awaitReleased(RELEASE_WAIT_MILLIS)
                    if (!isEnded.get()) load()
                }
        }
    }

    /** Ends the session for [reason]. Safe from any thread, any number of times. */
    fun end(reason: VoiceEnd) {
        if (!isEnded.compareAndSet(false, true)) return
        queue.close()
        val running = synchronized(lock) { capture }
        // Stopping waits for the microphone's thread, which may be the one calling.
        if (running != null) thread(name = "voice-stop") { running.stop() }
        events.ended(reason)
    }

    /** Waits up to [millis] for the session's threads to end and free the models. */
    fun awaitReleased(millis: Long) {
        val threads = synchronized(lock) { listOfNotNull(loader, recognizer) }
        for (each in threads) each.join(millis)
    }

    private fun load() {
        val files = model.files(config.detector)
        if (!store.hasAll(files)) return end(VoiceEnd.ModelMissing)
        val segmenter: SpeechSegmenter
        val transcriber: Transcriber
        try {
            segmenter =
                SpeechSegmenter(
                    store.file(config.detector.model),
                    config.detector,
                    config.threshold,
                    config.silenceSeconds,
                    config.raise,
                )
        } catch (failure: RuntimeException) {
            Log.w(TAG, "The speech detector didn't load", failure)
            return end(VoiceEnd.ModelFailed)
        }
        try {
            transcriber = Transcriber.load(store, model.parakeet)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Parakeet didn't load", failure)
            segmenter.release()
            return end(VoiceEnd.ModelFailed)
        } catch (failure: OutOfMemoryError) {
            Log.w(TAG, "Parakeet didn't fit in memory", failure)
            segmenter.release()
            return end(VoiceEnd.ModelFailed)
        }
        val watch = SpokenStopWatch(config.threshold, config.detector.window)
        val microphone =
            MicCapture(
                context,
                source,
                onAudio = { samples, _, first ->
                    if (firstCaptureSample < 0) firstCaptureSample = first
                    hear(segmenter, watch, samples)
                },
                onEnd = { ending -> captureEnded(segmenter, ending) },
            )
        val started =
            synchronized(lock) {
                if (isEnded.get()) {
                    false
                } else {
                    capture = microphone
                    recognizer = thread(name = "voice-recognizer") { recognize(transcriber) }
                    true
                }
            }
        if (!started) {
            segmenter.release()
            transcriber.release()
            return
        }
        if (!microphone.start()) {
            // The microphone never started, so its thread won't free the detector.
            segmenter.release()
            val granted =
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            end(
                when {
                    microphone.endReason == CaptureEnd.InputChanged -> VoiceEnd.InputChanged
                    granted -> VoiceEnd.MicFailed
                    else -> VoiceEnd.MicDenied
                }
            )
            return
        }
        if (!isEnded.get()) events.listening()
    }

    /** On the microphone's thread: judges [samples] and queues what the recognizer should hear. */
    private fun hear(segmenter: SpeechSegmenter, watch: SpokenStopWatch, samples: FloatArray) {
        if (isEnded.get()) return
        val heard =
            segmenter.accept(samples) { probability ->
                if (probability >= minOf(config.threshold, SpokenStopWatch.STOP_THRESHOLD))
                    lastSpeechSample = segmenter.position
                if (watch.accept(probability, segmenter.isSpeaking)) {
                    val now = segmenter.position
                    queue.checkTail(
                        segmenter.recent(now - SpokenStopWatch.tailSamples, now),
                        lastSpeechSample,
                    )
                }
            }
        for (utterance in heard.utterances) {
            val accepted =
                if (utterance.isShort) queue.checkShort(utterance.samples, utterance.end)
                else queue.addTurn(utterance)
            if (!accepted) {
                end(VoiceEnd.Overloaded)
                return
            }
        }
        val readout = if (config.showLevels) readout(segmenter, samples) else null
        events.level(loudness(heard.levelled), segmenter.isSpeaking, readout)
    }

    /** On the microphone's thread, after its last audio: frees the detector. */
    private fun captureEnded(segmenter: SpeechSegmenter, ending: CaptureEnd) {
        segmenter.release()
        when (ending) {
            CaptureEnd.Stopped -> Unit
            CaptureEnd.Silenced -> end(VoiceEnd.MicTaken)
            CaptureEnd.InputChanged -> end(VoiceEnd.InputChanged)
            CaptureEnd.Failed -> end(VoiceEnd.MicFailed)
        }
    }

    /** The recognizer's thread: decodes the queue and frees Parakeet when listening ends. */
    private fun recognize(transcriber: Transcriber) {
        try {
            val decoder =
                VoiceDecoder(queue, { transcriber.transcribe(it).text }, ::spokenStop) {
                    if (!isEnded.get()) events.turn(it)
                }
            while (!isEnded.get()) queue.take(WAIT_MILLIS)?.let(decoder::decode)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Recognition failed", failure)
            end(VoiceEnd.ModelFailed)
        } finally {
            transcriber.release()
        }
    }

    /** A spoken Stop: the main process stops every device first, then listening ends. */
    private fun spokenStop(lastSpeechSample: Long) {
        if (isEnded.get()) return
        val start = synchronized(lock) { capture?.startedAt } ?: 0L
        events.spokenStop(
            if (start > 0)
                start +
                    (firstCaptureSample.coerceAtLeast(0L) + lastSpeechSample) * 1000 / SAMPLE_RATE
            else 0L
        )
        end(VoiceEnd.Stop)
    }

    private companion object {
        const val TAG = "Voice"

        /** How long the recognizer waits for work before checking whether the session ended. */
        const val WAIT_MILLIS = 500L

        /** How long a new session waits for the one before it to free its models. */
        const val RELEASE_WAIT_MILLIS = 10_000L

        /** The test builds' readout: the microphone's level, the noise, the gain, the detector. */
        fun readout(segmenter: SpeechSegmenter, samples: FloatArray): String {
            val noise = segmenter.noiseDb?.let { "%.0f".format(Locale.ROOT, it) } ?: "–"
            return "mic %.0f dBFS · noise %s · gain %+.0f dB · speech %.2f"
                .format(
                    Locale.ROOT,
                    levelDb(samples),
                    noise,
                    segmenter.gainDb,
                    segmenter.probability,
                )
        }
    }
}

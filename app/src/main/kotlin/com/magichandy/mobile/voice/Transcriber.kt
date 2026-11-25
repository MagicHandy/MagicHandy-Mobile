package com.magichandy.mobile.voice

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.util.concurrent.atomic.AtomicBoolean

/** A finished transcription and how long the recognizer took. */
data class Transcription(val text: String, val millis: Long)

/**
 * Parakeet loaded in memory through sherpa-onnx. It finds the language by itself. Not thread-safe:
 * use one thread at a time.
 */
class Transcriber private constructor(private val recognizer: OfflineRecognizer) {
    private val released = AtomicBoolean(false)

    /** What [samples] say, and how long the transcription took. Empty audio says nothing. */
    fun transcribe(samples: FloatArray): Transcription {
        check(!released.get()) { "Parakeet was released" }
        if (samples.isEmpty()) return Transcription("", 0)
        val started = SystemClock.elapsedRealtime()
        val stream = recognizer.createStream()
        val text =
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        return Transcription(text, SystemClock.elapsedRealtime() - started)
    }

    /** Frees the native model. Safe to call twice. */
    fun release() {
        if (released.compareAndSet(false, true)) recognizer.release()
    }

    companion object {
        private const val MIN_THREADS = 2
        private const val MAX_THREADS = 4
        private const val FEATURES = 80

        /** Half the phone's cores, two to four, leaving room for the microphone and the app. */
        fun threads(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(MIN_THREADS, MAX_THREADS)

        /** Loads [files] from [store] on [threads] CPU threads. Slow: seconds, and up to GBs. */
        fun load(store: ModelStore, files: ParakeetFiles, threads: Int = threads()): Transcriber {
            val model =
                OfflineModelConfig(
                    transducer =
                        OfflineTransducerModelConfig(
                            encoder = store.file(files.encoder).path,
                            decoder = store.file(files.decoder).path,
                            joiner = store.file(files.joiner).path,
                        ),
                    numThreads = threads,
                    debug = false,
                    provider = "cpu",
                    modelType = "nemo_transducer",
                    tokens = store.file(files.tokens).path,
                )
            val config =
                OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURES),
                    modelConfig = model,
                    decodingMethod = "greedy_search",
                )
            return Transcriber(OfflineRecognizer(config = config))
        }
    }
}

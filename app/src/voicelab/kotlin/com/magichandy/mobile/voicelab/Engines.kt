package com.magichandy.mobile.voicelab

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.magichandy.mobile.voice.ModelStore
import com.magichandy.mobile.voice.Transcriber
import com.magichandy.mobile.voice.Transcription
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A Parakeet build loaded through voice input's own [Transcriber], so the page measures the code
 * the app ships. Not thread-safe: use one thread at a time.
 */
class ParakeetEngine
private constructor(val build: ParakeetBuild, private val parakeet: Transcriber) {
    /** What [samples] say, and how long the transcription took. Empty audio says nothing. */
    fun transcribe(samples: FloatArray): Transcription = parakeet.transcribe(samples)

    /** Frees the native model. Safe to call twice. */
    fun release() = parakeet.release()

    companion object {
        /** Loads [build] from [store] on [threads] CPU threads. Slow: seconds, and up to GBs. */
        fun load(store: ModelStore, build: ParakeetBuild, threads: Int): ParakeetEngine =
            ParakeetEngine(build, Transcriber.load(store, build.parakeet, threads))
    }
}

/** Google ML Kit's two modes. */
enum class KitMode(val title: String, val short: String, val note: String, val value: Int) {
    Basic(
        "Google ML Kit · Basic",
        "ML Kit Basic",
        "On-device speech model",
        SpeechRecognizerOptions.Mode.MODE_BASIC,
    ),
    Advanced(
        "Google ML Kit · Advanced",
        "ML Kit Advanced",
        "Gemini Nano · only while this page is open",
        SpeechRecognizerOptions.Mode.MODE_ADVANCED,
    ),
}

/** The languages the page offers ML Kit; Parakeet finds the language by itself. */
enum class KitLanguage(val title: String, val tag: String) {
    English("English (US)", "en-US"),
    Spanish("Spanish (Spain)", "es-ES"),
    Portuguese("Portuguese (Brazil)", "pt-BR"),
    Japanese("Japanese", "ja-JP"),
    Chinese("Chinese (Simplified)", "cmn-Hans-CN");

    val locale: Locale
        get() = Locale.forLanguageTag(tag)
}

/** One ML Kit client for a mode and language. Close it when the page changes either. */
class KitEngine(val mode: KitMode, language: KitLanguage) : AutoCloseable {
    val recognizer: SpeechRecognizer =
        SpeechRecognition.getClient(
            speechRecognizerOptions {
                locale = language.locale
                preferredMode = mode.value
            }
        )

    /**
     * Transcribes a WAV file in one go and returns the final text, or throws [GenAiException] when
     * ML Kit reports an error. Gives up after [TIMEOUT_MILLIS].
     */
    suspend fun transcribeFile(file: File): Transcription {
        val started = SystemClock.elapsedRealtime()
        val parts = mutableListOf<String>()
        var failure: GenAiException? = null
        val finished =
            withTimeoutOrNull(TIMEOUT_MILLIS) {
                recognizer
                    .startRecognition(
                        speechRecognizerRequest { audioSource = AudioSource.fromFile(file) }
                    )
                    .transformWhile { response ->
                        emit(response)
                        response !is SpeechRecognizerResponse.CompletedResponse &&
                            response !is SpeechRecognizerResponse.ErrorResponse
                    }
                    .collect { response ->
                        when (response) {
                            is SpeechRecognizerResponse.FinalTextResponse -> parts += response.text
                            is SpeechRecognizerResponse.ErrorResponse -> failure = response.e
                            else -> Unit
                        }
                    }
            }
        failure?.let { throw it }
        if (finished == null) throw IOException("ML Kit didn't finish within a minute")
        return Transcription(
            parts.joinToString(" ").trim(),
            SystemClock.elapsedRealtime() - started,
        )
    }

    override fun close() = recognizer.close()

    private companion object {
        const val TIMEOUT_MILLIS = 60_000L
    }
}

/** What an ML Kit stream reports while it listens. */
sealed interface KitEvent {
    data class Partial(val text: String) : KitEvent

    /** A final text, and when it arrived on the elapsed-realtime clock. */
    data class Final(val text: String, val at: Long) : KitEvent

    data class Failed(val error: GenAiException) : KitEvent

    data object Done : KitEvent
}

/**
 * Streams the page's own microphone audio into ML Kit through a pipe, so ML Kit and Parakeet hear
 * exactly the same samples. A slow or stopped ML Kit never holds up the microphone: audio it can't
 * take yet is dropped from the oldest end.
 */
class KitStream(
    private val engine: KitEngine,
    scope: CoroutineScope,
    private val onEvent: (KitEvent) -> Unit,
) {
    private val pipe = ParcelFileDescriptor.createPipe()
    private val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
    private val audio =
        Channel<ByteArray>(QUEUED_CHUNKS, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val writer: Job =
        scope.launch(Dispatchers.IO) {
            try {
                for (chunk in audio) output.write(chunk)
            } catch (_: IOException) {
                // ML Kit closed its end of the pipe; nothing more to deliver.
            } finally {
                runCatching { output.close() }
            }
        }

    private val reader: Job = scope.launch {
        val source = AudioSource.fromPfd(pipe[0], AudioSource.Mode.STREAMING)
        try {
            engine.recognizer
                .startRecognition(speechRecognizerRequest { audioSource = source })
                .transformWhile { response ->
                    emit(response)
                    response !is SpeechRecognizerResponse.CompletedResponse &&
                        response !is SpeechRecognizerResponse.ErrorResponse
                }
                .collect { response ->
                    when (response) {
                        is SpeechRecognizerResponse.PartialTextResponse ->
                            onEvent(KitEvent.Partial(response.text))
                        is SpeechRecognizerResponse.FinalTextResponse ->
                            onEvent(KitEvent.Final(response.text, SystemClock.elapsedRealtime()))
                        is SpeechRecognizerResponse.ErrorResponse ->
                            onEvent(KitEvent.Failed(response.e))
                        is SpeechRecognizerResponse.CompletedResponse -> onEvent(KitEvent.Done)
                        else -> Unit
                    }
                }
        } finally {
            audio.close()
            runCatching { source.close() }
        }
    }

    /** Queues 16-bit samples for ML Kit; called from the capture thread. */
    fun write(pcm: ShortArray) {
        audio.trySend(toBytes(pcm))
    }

    /** Ends the stream: no more audio, then ML Kit finishes what it has. */
    suspend fun stop() {
        audio.close()
        if (withTimeoutOrNull(STOP_WAIT_MILLIS) { writer.join() } == null) {
            writer.cancel()
            runCatching { output.close() }
        }
        if (withTimeoutOrNull(STOP_WAIT_MILLIS) { reader.join() } == null) {
            runCatching { engine.recognizer.stopRecognition() }
            reader.cancel()
        }
    }

    private companion object {
        /** About 4 seconds of 64 ms chunks. */
        const val QUEUED_CHUNKS = 64
        const val STOP_WAIT_MILLIS = 3_000L
    }
}

/** A sentence for an ML Kit error, naming the cases the test is looking for. */
fun describe(error: Throwable): String =
    if (error is GenAiException) {
        when (error.errorCode) {
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED ->
                "Stopped: Google allows it only while the app is on screen."
            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.NOT_SUPPORTED -> "Not available on this phone."
            GenAiException.ErrorCode.BUSY -> "Busy. Try again in a moment."
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
                "Google's usage limit for this app was reached."
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "Needs a system update."
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "Not enough storage for the model."
            GenAiException.ErrorCode.AUDIO_BUFFER_OVERFLOW ->
                "Audio arrived faster than it could keep up."
            else -> "Error " + error.errorCode + ": " + (error.message ?: "no details")
        }
    } else {
        // Name where it happened: an exception without a message says little on its own.
        val frame =
            error.stackTrace.firstOrNull()?.let {
                " in " + it.className.substringAfterLast('.') + "." + it.methodName
            } ?: ""
        val cause = error.cause?.let { "; caused by " + it.javaClass.simpleName } ?: ""
        (error.message ?: error.javaClass.simpleName) + frame + cause
    }

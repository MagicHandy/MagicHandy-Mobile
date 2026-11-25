package com.magichandy.mobile.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Why a [MicCapture] ended. */
enum class CaptureEnd {
    /** [MicCapture.stop] was called. */
    Stopped,

    /** Android silenced the recording: a call, another app, or the microphone privacy switch. */
    Silenced,

    /** Reading failed, as when Android's audio service restarts. */
    Failed,
}

/**
 * Records the microphone at 16 kHz mono on its own thread and hands each chunk to [onAudio] as
 * floats and as the raw 16-bit samples, with the index of its first sample. [startedAt] is the
 * elapsed-realtime clock when recording started, so a sample's time is about [startedAt] plus its
 * index over [SAMPLE_RATE].
 *
 * [onEnd] runs once on the capture thread after its last [onAudio], with the reason, so whatever
 * [onAudio] feeds can be flushed and released there without racing it. Call [stop] off the main
 * thread: it waits for the capture thread.
 */
class MicCapture(
    private val context: Context,
    private val source: MicSource,
    private val onAudio: (samples: FloatArray, pcm: ShortArray, first: Long) -> Unit,
    private val onEnd: (CaptureEnd) -> Unit = {},
) {
    /** Null while recording; set once, to the reason the recording ends. */
    private val ending = AtomicReference<CaptureEnd?>(null)

    /**
     * Guards [record] between [stop] and the capture thread's release; never held while reading.
     */
    private val recorderLock = Any()
    private var record: AudioRecord? = null
    @Volatile private var sessionId = 0
    private var worker: Thread? = null
    private val audio = context.getSystemService(AudioManager::class.java)

    /**
     * Notes when Android silences this recording for a call or another app. It runs on the main
     * thread, so it only records the reason: the capture thread stops within one chunk, since a
     * silenced recording still delivers (silent) audio.
     */
    private val silencing =
        object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                if (isSilenced(configs)) ending.compareAndSet(null, CaptureEnd.Silenced)
            }
        }

    @Volatile
    var startedAt: Long = 0L
        private set

    /**
     * Starts recording, and returns once the recorder is running. False when the microphone
     * permission is missing or the recorder couldn't open or start.
     */
    fun start(): Boolean {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        val minimum =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        val opened =
            try {
                AudioRecord(
                    source.androidSource,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum, CHUNK * Short.SIZE_BYTES * BUFFER_CHUNKS),
                )
            } catch (_: SecurityException) {
                return false
            }
        if (opened.state != AudioRecord.STATE_INITIALIZED) {
            opened.release()
            return false
        }
        // Listen for silencing before recording starts, so the change that starts it isn't missed.
        sessionId = opened.audioSessionId
        audio.registerAudioRecordingCallback(silencing, null)
        val started =
            try {
                opened.startRecording()
                opened.recordingState == AudioRecord.RECORDSTATE_RECORDING
            } catch (failure: IllegalStateException) {
                Log.w(TAG, "The recorder didn't start", failure)
                false
            }
        if (!started) {
            audio.unregisterAudioRecordingCallback(silencing)
            opened.release()
            return false
        }
        startedAt = SystemClock.elapsedRealtime()
        synchronized(recorderLock) { record = opened }
        // A call that was already running silences the recording from its first chunk.
        if (isSilenced(audio.activeRecordingConfigurations)) {
            ending.compareAndSet(null, CaptureEnd.Silenced)
        }
        worker = thread(name = "voice-mic") { capture(opened) }
        return true
    }

    /**
     * Stops recording and waits for the capture thread to end. Returns false if it was still
     * running after two seconds, or if called on the capture thread itself; it then ends by itself
     * and still calls [onEnd].
     */
    fun stop(): Boolean {
        if (ending.compareAndSet(null, CaptureEnd.Stopped)) {
            // Stopping the recorder returns a read that's waiting for audio.
            synchronized(recorderLock) { runCatching { record?.stop() } }
        }
        val capturing = worker ?: return true
        if (capturing === Thread.currentThread()) return false
        capturing.join(STOP_WAIT_MILLIS)
        return !capturing.isAlive
    }

    private fun isSilenced(configs: List<AudioRecordingConfiguration>): Boolean = configs.any {
        it.clientAudioSessionId == sessionId && it.isClientSilenced
    }

    private fun capture(recorder: AudioRecord) {
        val chunk = ShortArray(CHUNK)
        var position = 0L
        var emptyReads = 0
        try {
            while (ending.get() == null) {
                val read = recorder.read(chunk, 0, CHUNK)
                if (read < 0 || read == 0 && ++emptyReads > MAX_EMPTY_READS) {
                    ending.compareAndSet(null, CaptureEnd.Failed)
                    break
                }
                if (read == 0) continue
                emptyReads = 0
                val pcm = chunk.copyOf(read)
                onAudio(FloatArray(read) { pcm[it] / FULL_SCALE }, pcm, position)
                position += read
            }
        } catch (failure: RuntimeException) {
            // A failure in what the audio feeds, such as the native detector, ends the recording
            // as failed rather than taking the app down.
            Log.w(TAG, "Recording failed", failure)
            ending.compareAndSet(null, CaptureEnd.Failed)
        } finally {
            audio.unregisterAudioRecordingCallback(silencing)
            synchronized(recorderLock) {
                record = null
                runCatching { recorder.stop() }
                recorder.release()
            }
            onEnd(ending.get() ?: CaptureEnd.Failed)
        }
    }

    private companion object {
        /** 64 ms of audio per read: whole windows for both speech detectors. */
        const val CHUNK = 1024
        const val BUFFER_CHUNKS = 8
        const val STOP_WAIT_MILLIS = 2_000L

        /**
         * A blocking read returns nothing only when the recorder has stopped; give up after this.
         */
        const val MAX_EMPTY_READS = 50
        const val TAG = "Voice"
    }
}

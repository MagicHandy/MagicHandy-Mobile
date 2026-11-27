package com.magichandy.mobile.voicelab

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import androidx.core.content.edit
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.magichandy.mobile.core.api.Detector
import com.magichandy.mobile.core.api.MicSource
import com.magichandy.mobile.core.api.SpeechLevel
import com.magichandy.mobile.core.api.isChatStopMessage
import com.magichandy.mobile.voice.CaptureEnd
import com.magichandy.mobile.voice.MicCapture
import com.magichandy.mobile.voice.ModelStore
import com.magichandy.mobile.voice.SAMPLE_RATE
import com.magichandy.mobile.voice.SILENCE_DB
import com.magichandy.mobile.voice.SpeechSegmenter
import com.magichandy.mobile.voice.Transcription
import com.magichandy.mobile.voice.Utterance
import com.magichandy.mobile.voice.levelDb
import com.magichandy.mobile.voice.loudness
import com.magichandy.mobile.voice.model
import com.magichandy.mobile.voice.onThisPhone
import com.magichandy.mobile.voice.toFloats
import com.magichandy.mobile.voice.toPcm
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The voice test: model downloads, the engines, hands-free listening and clips. One per process, so
 * listening carries on while the page is closed or the screen is off. It measures only; it never
 * sends a message or touches a device.
 */
class VoiceLab private constructor(private val context: Application) : LabActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Parakeet runs on one thread, so transcriptions queue rather than compete. */
    private val asr =
        Executors.newSingleThreadExecutor { Thread(it, "voice-lab-asr") }.asCoroutineDispatcher()

    private val labFiles = File(context.noBackupFilesDir, "voice-lab")
    private val store = ModelStore(labFiles, freeBytes = ::allocatableBytes)
    private val settings = LabSettings(context)
    private val mutableState = MutableStateFlow(settings.restore())
    val state: StateFlow<LabState> = mutableState

    private val downloads = mutableMapOf<ParakeetBuild, Job>()
    private val kitEngines = mutableMapOf<KitMode, KitEngine>()
    private var parakeet: ParakeetEngine? = null
    // Claimed with compare-and-set: a recording can end by itself on the capture thread while
    // the page stops it.
    private val session = AtomicReference<HandsFree?>(null)
    private val clip = AtomicReference<ClipRecording?>(null)
    private var vitalsJob: Job? = null
    private var visible = false
    private var listeningSince: Long? = null
    private val ids = AtomicLong(1)

    /** The last few errors' stack traces, for the shared report and the log. */
    private val traces = ArrayDeque<String>()

    init {
        refreshBuilds()
        refreshKits()
    }

    // Downloads

    override fun download(build: ParakeetBuild) {
        if (downloads.values.any { it.isActive }) {
            notice("Wait for the other download to finish.")
            return
        }
        downloads[build] = scope.launch {
            setBuild(build, Fetch.Running(0, build.bytes))
            var shown = 0L
            try {
                store.download(build.files) { done, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - shown >= PROGRESS_MILLIS || done == total) {
                        shown = now
                        setBuild(build, Fetch.Running(done, total))
                    }
                }
                setBuild(build, Fetch.Ready)
            } catch (cancelled: CancellationException) {
                setBuild(build, Fetch.Missing)
                throw cancelled
            } catch (failure: IOException) {
                setBuild(build, Fetch.Failed(failure.message ?: "The download failed"))
            }
        }
    }

    override fun cancelDownload(build: ParakeetBuild) {
        downloads.remove(build)?.cancel()
    }

    override fun delete(build: ParakeetBuild) {
        if (state.value.isRunning) return
        scope.launch {
            withContext(asr) {
                if (parakeet?.build == build) {
                    parakeet?.release()
                    parakeet = null
                }
            }
            // The int8 and 4-bit builds share their decoder, joiner and tokens.
            val kept =
                ParakeetBuild.entries
                    .filter { it != build && store.hasAll(it.files) }
                    .flatMap { it.files }
                    .toSet()
            store.delete(build.files.filter { it !in kept })
            mutableState.update { it.copy(checkedBuilds = it.checkedBuilds - build) }
            setBuild(build, Fetch.Missing)
            save()
        }
    }

    override fun toggle(build: ParakeetBuild) = change {
        it.copy(checkedBuilds = it.checkedBuilds.toggled(build))
    }

    private fun refreshBuilds() {
        for (build in ParakeetBuild.entries) {
            setBuild(build, if (store.hasAll(build.files)) Fetch.Ready else Fetch.Missing)
        }
    }

    private fun setBuild(build: ParakeetBuild, fetch: Fetch) {
        mutableState.update { it.copy(builds = it.builds + (build to fetch)) }
    }

    // ML Kit

    override fun downloadKit(mode: KitMode) {
        val engine = kitEngines[mode] ?: return
        scope.launch {
            var total: Long? = null
            try {
                engine.recognizer.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            total = status.bytesToDownload
                            setKit(mode, KitStatus.Downloading(0, total))
                        }
                        is DownloadStatus.DownloadProgress ->
                            setKit(mode, KitStatus.Downloading(status.totalBytesDownloaded, total))
                        is DownloadStatus.DownloadCompleted -> setKit(mode, KitStatus.Ready)
                        is DownloadStatus.DownloadFailed ->
                            setKit(mode, KitStatus.Failed(failed("ML Kit download", status.e)))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                setKit(mode, KitStatus.Failed(failed("ML Kit download", failure)))
            }
        }
    }

    override fun toggle(mode: KitMode) = change {
        it.copy(checkedKits = it.checkedKits.toggled(mode))
    }

    override fun setLanguage(language: KitLanguage) {
        if (state.value.isRunning) return
        change { it.copy(language = language) }
        refreshKits()
    }

    /** Opens a client per mode for the current language and asks Google what's available. */
    private fun refreshKits() {
        kitEngines.values.forEach { runCatching { it.close() } }
        kitEngines.clear()
        val language = state.value.language
        for (mode in KitMode.entries) {
            setKit(mode, KitStatus.Checking)
            val engine =
                try {
                    KitEngine(mode, language)
                } catch (failure: Exception) {
                    setKit(
                        mode,
                        KitStatus.Failed(failed("ML Kit " + mode.name + " client", failure)),
                    )
                    continue
                }
            kitEngines[mode] = engine
            scope.launch {
                val status =
                    try {
                        when (engine.recognizer.checkStatus()) {
                            FeatureStatus.AVAILABLE -> KitStatus.Ready
                            FeatureStatus.DOWNLOADABLE -> KitStatus.Downloadable
                            FeatureStatus.DOWNLOADING -> KitStatus.Downloading(0, null)
                            else -> KitStatus.Unavailable
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        KitStatus.Failed(failed("ML Kit " + mode.name + " status", failure))
                    }
                setKit(mode, status)
            }
        }
    }

    private fun setKit(mode: KitMode, status: KitStatus) {
        mutableState.update { it.copy(kits = it.kits + (mode to status)) }
    }

    // Settings

    override fun setMic(mic: MicSettings) {
        if (state.value.isRunning) return
        change { it.copy(mic = mic) }
    }

    override fun setMode(mode: LabMode) {
        if (state.value.isRunning) return
        change { it.copy(mode = mode) }
    }

    override fun moveLine(step: Int) = change {
        val line = Math.floorMod(it.line + step, LabScript.lines.size)
        it.copy(line = line, lineText = LabScript.lines[line])
    }

    override fun editLine(text: String) = change { it.copy(lineText = text) }

    override fun clear() = mutableState.update { it.copy(entries = emptyList()) }

    override fun dismissNotice() = mutableState.update { it.copy(notice = null) }

    /** The page asked for the microphone and the person said no. */
    fun microphoneRefused() = notice("Allow the microphone to test speech recognition.")

    /** The report [share] hands to the share sheet. */
    fun report(versionName: String): String =
        LabReport.build(state.value, versionName, synchronized(traces) { traces.toList() })

    /** Logs [failure] with its stack trace, keeps it for the report, and says what went wrong. */
    private fun failed(where: String, failure: Throwable): String {
        Log.w(TAG, where, failure)
        synchronized(traces) {
            traces.addLast(where + ": " + failure.stackTraceToString())
            while (traces.size > MAX_TRACES) traces.removeFirst()
        }
        return describe(failure)
    }

    private fun change(transform: (LabState) -> LabState) {
        mutableState.update(transform)
        save()
    }

    private fun save() = settings.save(state.value)

    private fun notice(text: String) = mutableState.update { it.copy(notice = text) }

    private fun busy(text: String?) = mutableState.update { it.copy(busy = text) }

    // Hands-free

    override fun startListening() {
        val current = state.value
        if (current.isRunning) return
        val build =
            ParakeetBuild.entries.firstOrNull {
                it in current.checkedBuilds && current.builds[it] == Fetch.Ready
            }
        val kit =
            KitMode.entries.firstOrNull {
                it in current.checkedKits && current.kits[it] == KitStatus.Ready
            }
        if (build == null && kit == null) {
            notice("Check an engine that's ready first.")
            return
        }
        val mic = current.mic
        scope.launch {
            try {
                if (!store.has(mic.detector.model)) {
                    busy("Getting the " + mic.detector.title + " speech detector…")
                    store.download(listOf(mic.detector.model)) { _, _ -> }
                }
                if (build != null) load(build)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                busy(null)
                notice("Couldn't start: " + failed("Start listening", failure))
                return@launch
            }
            busy(null)
            begin(build, kit, mic)
        }
    }

    private fun begin(build: ParakeetBuild?, kit: KitMode?, mic: MicSettings) {
        val source = mic.source.onThisPhone(context)
        val segmenter =
            SpeechSegmenter(
                store.file(mic.detector.model),
                mic.detector,
                mic.threshold,
                mic.silenceSeconds,
                mic.raise,
            )
        val tags = mic.copy(source = source).tags + " · " + mic.detectorTag
        val hands = HandsFree(build, kit, tags, segmenter)
        val capture =
            MicCapture(
                context,
                source,
                onAudio = { samples, _, _ -> hands.hear(samples) },
                onEnd = hands::ended,
            )
        hands.capture = capture
        hands.stream = kit?.let { mode ->
            kitEngines[mode]?.let { KitStream(it, scope, hands::onKit) }
        }
        if (!session.compareAndSet(null, hands)) {
            // Already listening, from a second quick tap: drop this one.
            segmenter.release()
            scope.launch { hands.stream?.stop() }
            return
        }
        // Listening shows before the recorder starts, so a recorder that fails at once still
        // clears it afterwards.
        listeningSince = SystemClock.elapsedRealtime()
        val engines = listOfNotNull(build?.short, kit?.short).joinToString(" and ")
        mutableState.update {
            it.copy(
                listening = Listening(SystemClock.elapsedRealtime(), engines = engines),
                vitals = it.vitals.copy(batteryAtStart = battery(), minutes = 0),
            )
        }
        // The microphone service starts first, while the page is on screen, as Android requires.
        val inBackground = VoiceLabService.start(context)
        if (!capture.start()) {
            // No capture thread ran, so nothing else will release the detector.
            segmenter.release()
            if (session.compareAndSet(hands, null)) {
                scope.launch { hands.stream?.stop() }
                endListening()
            }
            notice("The microphone didn't open. Allow it, then try again.")
            return
        }
        if (session.get() !== hands) {
            // Stop listening came while the recorder was starting: stop it now that it runs. Its
            // capture thread then releases the detector.
            scope.launch(Dispatchers.IO) { capture.stop() }
            return
        }
        if (source != mic.source) note(RAW_MISSING)
        if (!inBackground) {
            note("Couldn't keep listening in the background, so it stops with the screen.")
        }
        watchVitals()
    }

    override fun stopListening() {
        val hands = session.getAndSet(null) ?: return
        tearDown(hands)
    }

    /** Stops [hands]'s recording and engines, off the main thread: stopping waits for capture. */
    private fun tearDown(hands: HandsFree) {
        scope.launch(Dispatchers.IO) {
            // The capture thread flushes the last turn and releases the detector as it ends.
            if (hands.capture?.stop() == false) Log.w(TAG, "Recording was slow to stop")
            hands.stream?.stop()
            hands.finish()
            // A new session may have started meanwhile; its service and state are its own.
            if (session.get() == null) endListening()
        }
    }

    private fun endListening() {
        VoiceLabService.stop(context)
        listeningSince = null
        mutableState.update { it.copy(listening = null) }
        if (!visible) vitalsJob?.cancel()
    }

    /** One hands-free session: the capture, the speech detector and the engines it feeds. */
    private inner class HandsFree(
        val build: ParakeetBuild?,
        val kit: KitMode?,
        val tags: String,
        val segmenter: SpeechSegmenter,
    ) {
        var capture: MicCapture? = null
        var stream: KitStream? = null
        private val bars = ArrayDeque<MeterBar>()
        private var shownLevels = 0L
        private var loudestDb = SILENCE_DB

        /**
         * Called on the capture thread once it has read its last chunk: the turn in progress is
         * sent, then the detector is released, so nothing else touches it afterwards.
         */
        fun ended(end: CaptureEnd) {
            try {
                segmenter.flush().forEach(::onUtterance)
            } catch (failure: RuntimeException) {
                failed("Last turn", failure)
            } finally {
                segmenter.release()
            }
            val reason = captureNote(end) ?: return
            note(reason)
            // Ended by itself: end the session too, unless Stop listening already has.
            if (session.compareAndSet(this, null)) tearDown(this)
        }

        /** Turns still waiting for ML Kit's text, and ML Kit text that came before its turn. */
        private val waiting = mutableListOf<Waiting>()
        private val early = mutableListOf<KitEvent.Final>()
        private val lock = Any()

        /**
         * Called on the capture thread with each chunk of audio. ML Kit hears the same levelled
         * audio as the detector and Parakeet.
         */
        fun hear(samples: FloatArray) {
            val heard = segmenter.accept(samples)
            loudestDb = maxOf(loudestDb, levelDb(samples))
            if (heard.levelled.isNotEmpty()) {
                stream?.write(toPcm(heard.levelled))
                bars.addLast(MeterBar(loudness(heard.levelled), isHot = segmenter.isSpeaking))
                while (bars.size > METER_BARS) bars.removeFirst()
            }
            val now = SystemClock.elapsedRealtime()
            if (now - shownLevels >= LEVEL_MILLIS) {
                shownLevels = now
                val reading =
                    Reading(
                        micDb = loudestDb,
                        raises = segmenter.raises,
                        noiseDb = segmenter.noiseDb,
                        gainDb = segmenter.gainDb,
                        probability = segmenter.probability,
                    )
                loudestDb = SILENCE_DB
                val shown = bars.toList()
                val speaking = segmenter.isSpeaking
                mutableState.update { state ->
                    val listening = state.listening
                    state.copy(
                        listening =
                            listening?.copy(
                                seconds = (now - listening.since) / MILLIS,
                                bars = shown,
                                speaking = speaking,
                                reading = reading,
                            )
                    )
                }
            }
            heard.utterances.forEach(::onUtterance)
        }

        fun onUtterance(utterance: Utterance) {
            val capture = capture ?: return
            val speechMillis = (utterance.end - utterance.start) * MILLIS / SAMPLE_RATE
            val audioMillis = (utterance.samples.size * MILLIS / SAMPLE_RATE).coerceAtLeast(1)
            val endedAt = capture.startedAt + utterance.end * MILLIS / SAMPLE_RATE
            val results =
                listOfNotNull(
                    build?.let { EngineResult(it.short) },
                    kit?.let { EngineResult(it.short) },
                )
            val id = addTurn(speechMillis, tags, null, results, utterance.level)
            if (kit != null && stream != null) {
                synchronized(lock) {
                    if (early.isNotEmpty()) {
                        val text = early.joinToString(" ") { it.text }.trim()
                        val latency = (early.last().at - endedAt).coerceAtLeast(0)
                        early.clear()
                        setKitText(id, text, latency)
                        waiting += Waiting(id, endedAt, filledAt = SystemClock.elapsedRealtime())
                    } else {
                        waiting += Waiting(id, endedAt)
                    }
                }
            }
            val engine = build ?: return
            scope.launch(asr) {
                val result =
                    try {
                        val transcription = parakeet?.transcribe(utterance.samples)
                        if (transcription == null) {
                            EngineResult(engine.short, error = "Parakeet wasn't loaded.")
                        } else {
                            val done = SystemClock.elapsedRealtime()
                            EngineResult(
                                engine.short,
                                text = transcription.text,
                                latencyMillis = done - endedAt,
                                processMillis = transcription.millis,
                                realTime = transcription.millis.toFloat() / audioMillis,
                                wouldStop = isChatStopMessage(transcription.text),
                            )
                        }
                    } catch (failure: Exception) {
                        EngineResult(
                            engine.short,
                            error = failed("Hands-free " + engine.short, failure),
                        )
                    }
                setResult(id, result)
            }
        }

        fun onKit(event: KitEvent) {
            when (event) {
                is KitEvent.Partial ->
                    mutableState.update {
                        it.copy(listening = it.listening?.copy(partial = event.text))
                    }
                is KitEvent.Final -> {
                    mutableState.update { it.copy(listening = it.listening?.copy(partial = null)) }
                    onFinal(event)
                }
                is KitEvent.Failed -> {
                    val reason = failed("ML Kit stream", event.error)
                    note("ML Kit stopped. $reason")
                    synchronized(lock) {
                        waiting
                            .filter { it.filledAt == null }
                            .forEach { failKit(it.turnId, reason) }
                        waiting.clear()
                    }
                }
                KitEvent.Done -> Unit
            }
        }

        /** Gives ML Kit's text to the latest turn that ended before it arrived. */
        private fun onFinal(final: KitEvent.Final) {
            if (final.text.isBlank()) return
            synchronized(lock) {
                val target = waiting.filter { it.endedAt <= final.at }.maxByOrNull { it.endedAt }
                if (target == null) {
                    early += final
                    return
                }
                // Older turns that never got ML Kit text keep none.
                waiting
                    .filter { it.endedAt < target.endedAt && it.filledAt == null }
                    .forEach { failKit(it.turnId, "No text") }
                waiting.removeAll { it.endedAt < target.endedAt }
                if (target.filledAt == null) {
                    target.filledAt = final.at
                    setKitText(target.turnId, final.text, final.at - target.endedAt)
                } else {
                    appendKitText(target.turnId, final.text)
                }
            }
        }

        fun finish() {
            synchronized(lock) {
                waiting.filter { it.filledAt == null }.forEach { failKit(it.turnId, "No text") }
                waiting.clear()
            }
        }

        private fun setKitText(turnId: Long, text: String, latency: Long) {
            val mode = kit ?: return
            setResult(
                turnId,
                EngineResult(
                    mode.short,
                    text = text,
                    latencyMillis = latency,
                    wouldStop = isChatStopMessage(text),
                ),
            )
        }

        private fun appendKitText(turnId: Long, text: String) {
            val mode = kit ?: return
            updateResult(turnId, mode.short) {
                val joined = ((it.text ?: "") + " " + text).trim()
                it.copy(text = joined, wouldStop = isChatStopMessage(joined))
            }
        }

        private fun failKit(turnId: Long, reason: String) {
            val mode = kit ?: return
            setResult(turnId, EngineResult(mode.short, error = reason))
        }
    }

    private class Waiting(val turnId: Long, val endedAt: Long, var filledAt: Long? = null)

    // One clip, every engine

    override fun startClip() {
        if (state.value.isRunning) return
        val chosen = state.value.mic.source
        val recording = ClipRecording(chosen.onThisPhone(context))
        val capture =
            MicCapture(
                context,
                recording.source,
                onAudio = { samples, pcm, _ -> recording.add(samples, pcm) },
                onEnd = { end ->
                    // Keep what was recorded before Android took the microphone away.
                    captureNote(end)?.let {
                        note(it)
                        finish(recording)
                    }
                },
            )
        recording.capture = capture
        if (!clip.compareAndSet(null, recording)) return
        // Recording shows before the recorder starts, so a recorder that fails at once still
        // clears it afterwards.
        mutableState.update { it.copy(clip = ClipPhase.Recording(SystemClock.elapsedRealtime())) }
        if (!capture.start()) {
            if (clip.compareAndSet(recording, null)) {
                mutableState.update { it.copy(clip = ClipPhase.Idle) }
            }
            notice("The microphone didn't open. Allow it, then try again.")
            return
        }
        if (recording.source != chosen) note(RAW_MISSING)
        // A clip is one line; stop by itself rather than record indefinitely.
        scope.launch {
            delay(MAX_CLIP_MILLIS)
            finish(recording)
        }
    }

    override fun finishClip() {
        clip.get()?.let(::finish)
    }

    /**
     * Ends [recording] and transcribes it, once: whichever comes first of Finish, the time limit
     * and the microphone being taken away.
     */
    private fun finish(recording: ClipRecording) {
        if (!clip.compareAndSet(recording, null)) return
        scope.launch(Dispatchers.IO) {
            recording.capture?.stop()
            val pcm = recording.pcm()
            if (pcm.size < SAMPLE_RATE * MIN_CLIP_MILLIS / MILLIS) {
                mutableState.update { it.copy(clip = ClipPhase.Idle) }
                notice("That clip was too short. Hold Record a little longer.")
                return@launch
            }
            val current = state.value
            transcribeAll(
                toFloats(pcm),
                current.lineText.trim().ifBlank { null },
                current.mic.copy(source = recording.source).tags,
            )
        }
    }

    override fun runSample() {
        if (state.value.isRunning) return
        scope.launch {
            try {
                if (!store.has(LabFiles.sample)) {
                    busy("Getting the sample recording…")
                    store.download(listOf(LabFiles.sample)) { _, _ -> }
                }
                busy(null)
                val sample = toFloats(readWav(store.file(LabFiles.sample)))
                transcribeAll(sample, null, "sample recording")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                busy(null)
                notice("The sample didn't run: " + failed("Sample", failure))
            }
        }
    }

    /**
     * Runs every checked, ready engine on the same audio, one after another, levelled first when
     * the page raises quiet speech.
     */
    private suspend fun transcribeAll(recorded: FloatArray, reference: String?, tags: String) {
        val current = state.value
        val builds =
            ParakeetBuild.entries.filter {
                it in current.checkedBuilds && current.builds[it] == Fetch.Ready
            }
        val kits =
            KitMode.entries.filter {
                it in current.checkedKits && current.kits[it] == KitStatus.Ready
            }
        if (builds.isEmpty() && kits.isEmpty()) {
            mutableState.update { it.copy(clip = ClipPhase.Idle) }
            notice("Check an engine that's ready first.")
            return
        }
        val levelled = levelClip(recorded, current.mic.raise)
        val samples = levelled.samples
        val speechMillis = (samples.size * MILLIS / SAMPLE_RATE).coerceAtLeast(1)
        val wav = File(context.cacheDir, "voice-lab/clip.wav")
        writeWav(wav, toPcm(samples))
        val results = builds.map { EngineResult(it.short) } + kits.map { EngineResult(it.short) }
        val id = addTurn(speechMillis, tags, reference, results, levelled.level)
        for (build in builds) {
            mutableState.update { it.copy(clip = ClipPhase.Transcribing(build.short)) }
            val result =
                try {
                    load(build)
                    val transcription = withContext(asr) { parakeet?.transcribe(samples) }
                    if (transcription == null) {
                        EngineResult(build.short, error = "Parakeet wasn't loaded.")
                    } else {
                        scored(build.short, transcription, speechMillis, reference, timed = true)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    EngineResult(build.short, error = failed("Clip " + build.short, failure))
                }
            setResult(id, result)
        }
        for (mode in kits) {
            mutableState.update { it.copy(clip = ClipPhase.Transcribing(mode.short)) }
            val engine = kitEngines[mode]
            val result =
                try {
                    if (engine == null) {
                        EngineResult(mode.short, error = "ML Kit isn't set up.")
                    } else {
                        val transcription = engine.transcribeFile(wav)
                        scored(mode.short, transcription, speechMillis, reference, timed = false)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    EngineResult(mode.short, error = failed("Clip " + mode.short, failure))
                }
            setResult(id, result)
        }
        mutableState.update { it.copy(clip = ClipPhase.Idle) }
    }

    private fun scored(
        engine: String,
        transcription: Transcription,
        speechMillis: Long,
        reference: String?,
        timed: Boolean,
    ) =
        EngineResult(
            engine,
            text = transcription.text,
            processMillis = transcription.millis,
            realTime = if (timed) transcription.millis.toFloat() / speechMillis else null,
            score = reference?.let { scoreTranscript(it, transcription.text) },
            wouldStop = isChatStopMessage(transcription.text),
        )

    /**
     * The microphone audio of one clip, recorded from [source] and gathered on the capture thread.
     */
    private inner class ClipRecording(val source: MicSource) {
        var capture: MicCapture? = null
        private val chunks = mutableListOf<ShortArray>()
        private val bars = ArrayDeque<MeterBar>()
        private var shown = 0L

        fun add(samples: FloatArray, pcm: ShortArray) {
            synchronized(chunks) { chunks += pcm }
            val level = loudness(samples)
            bars.addLast(MeterBar(level, isHot = level > HOT_LEVEL))
            while (bars.size > METER_BARS) bars.removeFirst()
            val now = SystemClock.elapsedRealtime()
            if (now - shown >= LEVEL_MILLIS) {
                shown = now
                val shownBars = bars.toList()
                mutableState.update { state ->
                    val phase = state.clip
                    if (phase is ClipPhase.Recording) {
                        state.copy(
                            clip =
                                phase.copy(
                                    seconds = (now - phase.since) / MILLIS,
                                    bars = shownBars,
                                )
                        )
                    } else {
                        state
                    }
                }
            }
        }

        fun pcm(): ShortArray =
            synchronized(chunks) {
                val all = ShortArray(chunks.sumOf { it.size })
                var offset = 0
                for (chunk in chunks) {
                    chunk.copyInto(all, offset)
                    offset += chunk.size
                }
                all
            }
    }

    // Engines

    /** Loads [build] into memory, releasing any other build first, and notes how long it took. */
    private suspend fun load(build: ParakeetBuild) {
        if (withContext(asr) { parakeet?.build == build }) return
        busy("Loading " + build.short + "…")
        try {
            val millis =
                withContext(asr) {
                    parakeet?.release()
                    parakeet = null
                    val started = SystemClock.elapsedRealtime()
                    parakeet = ParakeetEngine.load(store, build, parakeetThreads())
                    SystemClock.elapsedRealtime() - started
                }
            note(
                "Loaded " +
                    build.short +
                    " in " +
                    "%.1f".format(Locale.ROOT, millis / MILLIS.toFloat()) +
                    " s · memory " +
                    memoryMb() +
                    " MB"
            )
        } finally {
            busy(null)
        }
    }

    // Results

    private fun addTurn(
        speechMillis: Long,
        tags: String,
        reference: String?,
        results: List<EngineResult>,
        level: SpeechLevel?,
    ): Long {
        val id = ids.getAndIncrement()
        val turn =
            Turn(id, System.currentTimeMillis(), speechMillis, tags, reference, results, level)
        mutableState.update { it.copy(entries = listOf<LabEntry>(turn) + it.entries) }
        return id
    }

    private fun note(text: String) {
        val entry = LabNote(ids.getAndIncrement(), System.currentTimeMillis(), text)
        mutableState.update { it.copy(entries = listOf<LabEntry>(entry) + it.entries) }
    }

    private fun setResult(turnId: Long, result: EngineResult) =
        updateResult(turnId, result.engine) { result }

    private fun updateResult(turnId: Long, engine: String, change: (EngineResult) -> EngineResult) {
        mutableState.update { state ->
            state.copy(
                entries =
                    state.entries.map { entry ->
                        if (entry is Turn && entry.id == turnId) {
                            entry.copy(
                                results =
                                    entry.results.map {
                                        if (it.engine == engine) change(it) else it
                                    }
                            )
                        } else {
                            entry
                        }
                    }
            )
        }
    }

    // Vitals

    /** Called as the page shows and hides; ML Kit only works while it shows. */
    fun onVisible(isVisible: Boolean) {
        visible = isVisible
        if (isVisible) watchVitals() else if (session.get() == null) vitalsJob?.cancel()
    }

    private fun watchVitals() {
        if (vitalsJob?.isActive == true) return
        vitalsJob = scope.launch {
            while (isActive) {
                val battery = battery()
                val thermal = thermal()
                val memory = memoryMb()
                val minutes = listeningSince?.let { (SystemClock.elapsedRealtime() - it) / MINUTE }
                mutableState.update {
                    it.copy(
                        vitals =
                            it.vitals.copy(
                                battery = battery,
                                thermal = thermal,
                                memoryMb = memory,
                                minutes = minutes ?: it.vitals.minutes,
                            )
                    )
                }
                delay(VITALS_MILLIS)
            }
        }
    }

    private fun battery(): Int? =
        context
            .getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    private fun thermal(): String =
        when (context.getSystemService(PowerManager::class.java)?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Emergency"
            else -> "Normal"
        }

    private fun memoryMb(): Int = (Debug.getPss() / KILOBYTES_PER_MEGABYTE).toInt()

    /**
     * The bytes downloads may use: free space plus cached data Android can clear for them. If the
     * phone can't say, the download goes ahead unchecked rather than being refused.
     */
    private fun allocatableBytes(): Long {
        val storage = context.getSystemService(StorageManager::class.java) ?: return Long.MAX_VALUE
        labFiles.mkdirs()
        return try {
            storage.getAllocatableBytes(storage.getUuidForPath(labFiles))
        } catch (_: IOException) {
            Long.MAX_VALUE
        }
    }

    companion object {
        private const val MILLIS = 1_000L
        private const val MINUTE = 60_000L
        private const val PROGRESS_MILLIS = 250L
        private const val LEVEL_MILLIS = 100L
        private const val VITALS_MILLIS = 5_000L
        private const val METER_BARS = 24

        /** A clip's meter bar is hot above about −39 dBFS. */
        private const val HOT_LEVEL = 0.35f
        private const val MAX_CLIP_MILLIS = 30_000L
        private const val RAW_MISSING =
            "This phone doesn't offer raw audio, so Raw recorded the Standard microphone."
        private const val MIN_CLIP_MILLIS = 300L
        private const val KILOBYTES_PER_MEGABYTE = 1024L
        private const val MAX_TRACES = 5
        private const val TAG = "VoiceLab"

        private const val MIN_PARAKEET_THREADS = 2
        private const val MAX_PARAKEET_THREADS = 4

        /** Half the phone's cores, two to four, leaving room for the microphone and the page. */
        private fun parakeetThreads(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(
                MIN_PARAKEET_THREADS,
                MAX_PARAKEET_THREADS,
            )

        /** Why a recording ended by itself, or null when it was stopped on purpose. */
        private fun captureNote(end: CaptureEnd): String? =
            when (end) {
                CaptureEnd.Stopped -> null
                CaptureEnd.Silenced ->
                    "Android gave the microphone to a call or another app, so recording stopped."
                CaptureEnd.InputChanged ->
                    "The built-in microphone is unavailable. Check the input route and start again."
                CaptureEnd.Failed -> "The microphone stopped working, so recording stopped."
            }

        // It holds only the application context, which lives exactly as long as the process.
        @SuppressLint("StaticFieldLeak") @Volatile private var instance: VoiceLab? = null

        /** The process's voice test. */
        fun get(context: Context): VoiceLab =
            instance
                ?: synchronized(this) {
                    instance
                        ?: VoiceLab(context.applicationContext as Application).also {
                            instance = it
                        }
                }
    }
}

private fun <T> Set<T>.toggled(item: T): Set<T> = if (item in this) this - item else this + item

/** The page's settings, kept between visits. Results aren't kept: share them instead. */
private class LabSettings(context: Context) {
    private val preferences = context.getSharedPreferences("voice_lab", Context.MODE_PRIVATE)

    fun restore(): LabState {
        val defaults = LabState()
        fun <E : Enum<E>> names(key: String, all: List<E>, fallback: Set<E>): Set<E> =
            preferences
                .getStringSet(key, null)
                ?.mapNotNull { name ->
                    all.firstOrNull { it.name == name }
                }
                ?.toSet() ?: fallback
        fun <E : Enum<E>> one(key: String, all: List<E>, fallback: E): E =
            preferences.getString(key, null)?.let { name -> all.firstOrNull { it.name == name } }
                ?: fallback
        val line = preferences.getInt("line", 0).coerceIn(0, LabScript.lines.lastIndex)
        return defaults.copy(
            checkedBuilds = names("builds", ParakeetBuild.entries, defaults.checkedBuilds),
            checkedKits = names("kits", KitMode.entries, defaults.checkedKits),
            language = one("language", KitLanguage.entries, defaults.language),
            mode = one("mode", LabMode.entries, defaults.mode),
            mic =
                MicSettings(
                    source = one("source", MicSource.entries, defaults.mic.source),
                    raise = preferences.getBoolean("raise", defaults.mic.raise),
                    distance = one("distance", Distance.entries, defaults.mic.distance),
                    deviceNearby = preferences.getBoolean("nearby", false),
                    detector = one("detector", Detector.entries, defaults.mic.detector),
                    threshold = preferences.getFloat("threshold", defaults.mic.threshold),
                    silenceSeconds = preferences.getFloat("silence", defaults.mic.silenceSeconds),
                ),
            line = line,
            lineText = LabScript.lines[line],
        )
    }

    fun save(state: LabState) {
        preferences.edit {
            putStringSet("builds", state.checkedBuilds.map { it.name }.toSet())
            putStringSet("kits", state.checkedKits.map { it.name }.toSet())
            putString("language", state.language.name)
            putString("mode", state.mode.name)
            putString("source", state.mic.source.name)
            putBoolean("raise", state.mic.raise)
            putString("distance", state.mic.distance.name)
            putBoolean("nearby", state.mic.deviceNearby)
            putString("detector", state.mic.detector.name)
            putFloat("threshold", state.mic.threshold)
            putFloat("silence", state.mic.silenceSeconds)
            putInt("line", state.line)
        }
    }
}

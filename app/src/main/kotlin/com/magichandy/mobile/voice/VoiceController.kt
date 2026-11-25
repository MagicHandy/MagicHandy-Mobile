package com.magichandy.mobile.voice

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.Message
import android.os.RemoteException
import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.AutopilotStatus
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StartupState
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.api.VoiceModel
import com.magichandy.mobile.core.api.maximumMessageLength
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice input's side in the main process (docs/voice-design.md §4–§8): what Chat and Settings show,
 * the model downloads, starting and ending the voice service, and the turns it hears.
 *
 * - **Stop:** a spoken Stop reaches the core before this hears of it ([VoiceLinkService]). Any Stop
 *   ends listening, and a turn heard under an older Stop epoch is dropped.
 * - **Turns** go to the chat that's open when they arrive. While a reply streams they wait, joined
 *   into one message; opening another chat or an interrupted reply discards them.
 * - **Downloads** run only while the app is on screen, and pause when it leaves.
 *
 * Its state changes on the main thread; the link's messages arrive on their own thread.
 */
internal class VoiceController(
    private val context: Context,
    private val core: MagicHandyCore,
    private val scope: CoroutineScope,
) : VoiceControls {
    private val folder = VoiceService.modelFolder(context)
    private val store = ModelStore(folder, freeBytes = ::freeBytes)
    private val tuning = VoiceTuning(context)
    private val ui = Dispatchers.Main.immediate
    private val mutableState: MutableStateFlow<VoiceState>
    private val mutableMeter = MutableStateFlow(VoiceMeter())

    override val state: StateFlow<VoiceState>
    override val meter: StateFlow<VoiceMeter> = mutableMeter.asStateFlow()

    /** The listening session, from the tap until it ends. */
    private class Session(val id: Int, val epoch: Long, val model: VoiceModel, val startedAt: Long)

    private var session: Session? = null
    private var sessions = 0

    /** The current session's id, for the link's thread; 0 when not listening. */
    private val current = AtomicInteger(0)
    private val lastHeard = AtomicLong(0)
    private var lastPlaying = 0L
    private var voiceProcess: IBinder? = null
    private val processDied = IBinder.DeathRecipient { scope.launch(ui) { voiceProcessDied() } }

    private var downloading: Job? = null

    /** Deleting what a cancelled download fetched; a new download waits for it. */
    private var discarding: Job? = null
    private var ticking: Job? = null
    private var isShown = false
    private var isSettled = false
    private var coreChoice: VoiceModel? = null

    /** The model to delete when listening ends: the one a finished switch replaced. */
    private var retiring: VoiceModel? = null

    /** Turns waiting for the streaming reply, and the chat they were heard in. */
    private val waiting = mutableListOf<String>()
    private var waitingChat: ChatId? = null

    /** When a turn was sent, until its reply is seen streaming. */
    private var sentAt: Long? = null

    /** The epoch of the session a Stop ended, until the Stop's own epoch is seen. */
    private var stoppedUnder: Long? = null

    init {
        val memory =
            ActivityManager.MemoryInfo()
                .also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                .totalMem
        mutableState =
            MutableStateFlow(
                VoiceState(
                    isOffered = memory >= VOICE_MEMORY_BYTES,
                    canChooseModel = memory >= FULL_MEMORY_BYTES,
                    showsHiddenSettings = tuning.isShown,
                    config = tuning.read(),
                )
            )
        state = mutableState.asStateFlow()
        scope.launch(ui) { core.state.collect { observe(it) } }
    }

    // The app

    /** The app came on screen: a paused download resumes. */
    fun appShown() = onUi {
        isShown = true
        if (isSettled) resumePaused()
    }

    /** The app left the screen: a download pauses, and won't start listening when it finishes. */
    fun appHidden() = onUi {
        isShown = false
        val download = mutableState.value.download ?: return@onUi
        if (download.phase == DownloadPhase.Running) {
            downloading?.cancel()
            downloading = null
            setDownload(download.copy(phase = DownloadPhase.Paused, startsListening = false))
        }
    }

    // What the UI asks

    override fun start() = onUi {
        val now = mutableState.value
        val model = now.installed
        if (now.listening != null || model == null) return@onUi
        if (!isGranted()) {
            notice(VoiceNotice(VoiceEnd.MicDenied, model))
            return@onUi
        }
        val config = tuning.read()
        if (!store.has(config.detector.model)) {
            fetchDetectorThenStart(config.detector)
            return@onUi
        }
        listen(model, if (isTalkBackOn()) config.copy(source = MicSource.EchoCancel) else config)
    }

    override fun end() = onUi { endListening(VoiceEnd.User) }

    override fun download(model: VoiceModel, andStart: Boolean) = onUi {
        val now = mutableState.value
        choose(model)
        val download = now.download
        if (download != null && download.model != model) discardDownload(download.model)
        when {
            now.installed == model -> if (andStart) start()
            download?.model == model && downloading?.isActive == true -> Unit
            else -> fetch(model, startsListening = andStart && isShown)
        }
    }

    override fun cancelDownload() = onUi {
        val download = mutableState.value.download ?: return@onUi
        discardDownload(download.model)
        choose(mutableState.value.installed ?: VoiceModel.Compact)
    }

    override fun retryDownload() = onUi {
        val download = mutableState.value.download ?: return@onUi
        fetch(
            download.model,
            startsListening = mutableState.value.installed == null && isShown,
        )
    }

    override fun remove() = onUi {
        endListening(VoiceEnd.User)
        val running = downloading
        downloading = null
        retiring = null
        mutableState.update { it.copy(installed = null, download = null, notice = null) }
        choose(VoiceModel.Compact)
        val before = discarding
        discarding =
            scope.launch(ui) {
                before?.join()
                running?.cancelAndJoin()
                withContext(Dispatchers.IO) { folder.deleteRecursively() }
            }
    }

    override fun dismissNotice() = onUi { mutableState.update { it.copy(notice = null) } }

    override fun microphoneRefused() = onUi {
        notice(VoiceNotice(VoiceEnd.MicDenied, mutableState.value.installed))
    }

    override fun tune(config: VoiceConfig) = onUi {
        tuning.write(config)
        mutableState.update { it.copy(config = tuning.read()) }
    }

    // The link

    /** A message from the voice process, on the link's thread. */
    fun receive(message: Message) {
        val id = message.arg1
        when (message.what) {
            VoiceProtocol.HELLO -> {
                val binder = message.replyTo?.binder
                if (binder != null) onUi { watch(binder) }
            }
            VoiceProtocol.LEVEL -> {
                if (id != current.get()) return
                val data = message.data
                val hearing = data.getBoolean(VoiceProtocol.HEARING)
                if (hearing) lastHeard.set(SystemClock.elapsedRealtime())
                val loudness = data.getFloat(VoiceProtocol.LOUDNESS)
                val readout = data.getString(VoiceProtocol.READOUT)
                mutableMeter.update {
                    VoiceMeter((it.levels + loudness).takeLast(METER_LEVELS), hearing, readout)
                }
            }
            VoiceProtocol.LISTENING ->
                onUi {
                    if (session?.id == id) setListening { it.copy(isReady = true) }
                }
            VoiceProtocol.TURN -> {
                val text = message.data.getString(VoiceProtocol.TEXT).orEmpty()
                onUi { heard(id, text) }
            }
            VoiceProtocol.ENDED -> {
                val reason = VoiceProtocol.reason(message)
                onUi { if (session?.id == id) endListening(reason) }
            }
            // The core has already stopped; the new Stop epoch ends listening.
            VoiceProtocol.SPOKEN_STOP -> Unit
        }
    }

    private fun watch(binder: IBinder) {
        if (binder == voiceProcess) return
        voiceProcess?.unlinkToDeath(processDied, 0)
        voiceProcess =
            try {
                binder.linkToDeath(processDied, 0)
                binder
            } catch (_: RemoteException) {
                // It's gone already.
                voiceProcessDied()
                null
            }
    }

    /** The voice process ended without a word: a native crash, or out of memory. */
    private fun voiceProcessDied() {
        voiceProcess = null
        if (session != null) endListening(VoiceEnd.ModelFailed)
    }

    // Listening

    private fun listen(model: VoiceModel, config: VoiceConfig) {
        val id = ++sessions
        val intent =
            config.into(
                Intent(context, VoiceService::class.java)
                    .setAction(VoiceService.ACTION_START)
                    .putExtra(VoiceService.EXTRA_SESSION, id)
                    .putExtra(VoiceService.EXTRA_MODEL, model.name)
            )
        try {
            context.startForegroundService(intent)
        } catch (refused: IllegalStateException) {
            // Android 12 and later refuse a foreground service started from the background.
            Log.w(TAG, "Couldn't start listening", refused)
            notice(VoiceNotice(VoiceEnd.MicDenied, model))
            return
        }
        val now = SystemClock.elapsedRealtime()
        session = Session(id, core.state.value.stopEpoch, model, now)
        current.set(id)
        lastHeard.set(now)
        core.setVoiceActive(true)
        mutableMeter.value = VoiceMeter()
        mutableState.update {
            it.copy(listening = VoiceListening(model), notice = null, endedByStop = null)
        }
        ticking?.cancel()
        ticking =
            scope.launch(ui) {
                while (isActive) {
                    delay(TICK_MILLIS)
                    tick()
                }
            }
    }

    private fun endListening(reason: VoiceEnd) {
        val ended = session ?: return
        session = null
        current.set(0)
        ticking?.cancel()
        ticking = null
        context.stopService(Intent(context, VoiceService::class.java))
        core.setVoiceActive(false)
        waiting.clear()
        waitingChat = null
        sentAt = null
        mutableMeter.value = VoiceMeter()
        if (reason == VoiceEnd.Stop) stoppedUnder = ended.epoch
        val notice =
            when (reason) {
                VoiceEnd.User,
                VoiceEnd.Stop -> null
                else -> VoiceNotice(reason, ended.model)
            }
        mutableState.update { it.copy(listening = null, notice = notice) }
        retiring?.let { old ->
            retiring = null
            if (old != mutableState.value.installed) deleteModel(old)
        }
    }

    private fun fetchDetectorThenStart(detector: Detector) {
        scope.launch(ui) {
            try {
                store.download(listOf(detector.model)) { _, _ -> }
                start()
            } catch (failure: IOException) {
                Log.w(TAG, "The speech detector didn't download", failure)
                notice(VoiceNotice(VoiceEnd.ModelMissing, mutableState.value.installed))
            }
        }
    }

    /** A turn from session [id]. */
    private fun heard(id: Int, text: String) {
        val listening = session ?: return
        val now = core.state.value
        // The Stop fence: nothing heard before a Stop reaches chat.
        if (listening.id != id || now.stopEpoch != listening.epoch) return
        lastHeard.set(SystemClock.elapsedRealtime())
        if (isReplyBusy(now) || waiting.isNotEmpty()) {
            if (waiting.isEmpty()) waitingChat = now.chat.chatId
            waiting += text
            setListening { it.copy(isWaiting = true) }
            return
        }
        send(text, now)
    }

    private fun send(text: String, now: AppState) {
        val blocked = now.chat.sendBlockedBy
        if (blocked != null) {
            setListening { it.copy(cantSend = blocked, isWaiting = false) }
            return
        }
        core.dispatch(UserIntent.SendMessage(text.take(maximumMessageLength)))
        sentAt = SystemClock.elapsedRealtime()
        setListening { it.copy(cantSend = null, isWaiting = false) }
    }

    /**
     * True while a reply streams, or a sent turn hasn't started streaming yet: the core takes one
     * turn at a time and drops a message sent during another.
     */
    private fun isReplyBusy(now: AppState): Boolean {
        if (now.chat.reply is ReplyState.Streaming) return true
        val sent = sentAt ?: return false
        return SystemClock.elapsedRealtime() - sent < SEND_GRACE_MILLIS
    }

    /** Follows the core: Stops, the chosen model, the reply, and whether anything plays. */
    private fun observe(now: AppState) {
        val listening = session
        if (listening != null && now.stopEpoch != listening.epoch) endListening(VoiceEnd.Stop)
        stoppedUnder?.let { epoch ->
            if (now.stopEpoch > epoch) {
                stoppedUnder = null
                mutableState.update { it.copy(endedByStop = now.stopEpoch) }
            }
        }
        if (now.startup == StartupState.Ready) {
            if (!isSettled) {
                isSettled = true
                coreChoice = now.app.voiceModel
                settle(now.app.voiceModel)
            } else if (now.app.voiceModel != coreChoice) {
                coreChoice = now.app.voiceModel
                mutableState.update { it.copy(chosen = now.app.voiceModel) }
            }
        }
        if (isPlaying(now)) lastPlaying = SystemClock.elapsedRealtime()
        val reply = now.chat.reply
        if (reply is ReplyState.Streaming) sentAt = null
        if (waiting.isNotEmpty()) {
            when {
                now.chat.chatId != waitingChat || reply is ReplyState.Interrupted -> {
                    waiting.clear()
                    setListening { it.copy(isWaiting = false) }
                }
                !isReplyBusy(now) -> flushWaiting(now)
            }
        }
        if (now.chat.sendBlockedBy == null) {
            setListening { if (it.cantSend == null) it else it.copy(cantSend = null) }
        }
    }

    private fun flushWaiting(now: AppState) {
        val text = waiting.joinToString(" ")
        waiting.clear()
        waitingChat = null
        send(text, now)
    }

    /** Every few seconds: sends turns a quick reply left waiting, and ends 15 quiet minutes. */
    private fun tick() {
        val listening = session ?: return
        val now = core.state.value
        if (waiting.isNotEmpty() && !isReplyBusy(now)) flushWaiting(now)
        if (isPlaying(now)) lastPlaying = SystemClock.elapsedRealtime()
        val clock = SystemClock.elapsedRealtime()
        val quietSince = maxOf(lastHeard.get(), lastPlaying, listening.startedAt)
        val isReady = mutableState.value.listening?.isReady == true
        when {
            clock - quietSince >= QUIET_MILLIS -> endListening(VoiceEnd.Quiet)
            !isReady && clock - listening.startedAt >= READY_WAIT_MILLIS ->
                endListening(VoiceEnd.ModelFailed)
        }
    }

    // Models

    /**
     * Once the settings have loaded: finds the model on the phone and removes any other, in case
     * the app stopped mid-switch. A download it finds part done resumes while the app is on screen.
     */
    private fun settle(chosen: VoiceModel) {
        scope.launch(ui) {
            val found =
                withContext(Dispatchers.IO) {
                    val others = VoiceModel.entries - chosen
                    if (isComplete(chosen)) {
                        others.forEach { store.delete(it.parakeet.all) }
                        chosen
                    } else {
                        others.filterNot(::isComplete).forEach { store.delete(it.parakeet.all) }
                        others.firstOrNull(::isComplete)
                    }
                }
            val partial =
                if (found == chosen) 0L
                else withContext(Dispatchers.IO) { store.present(chosen.parakeet.all) }
            mutableState.update {
                it.copy(
                    installed = found,
                    chosen = chosen,
                    download =
                        if (partial > 0) {
                            VoiceDownload(
                                chosen,
                                partial,
                                chosen.files(Detector.Silero).sumOf { file -> file.bytes },
                                DownloadPhase.Paused,
                            )
                        } else {
                            null
                        },
                )
            }
            if (isShown) resumePaused()
        }
    }

    private fun isComplete(model: VoiceModel): Boolean = store.hasAll(model.files(Detector.Silero))

    private fun resumePaused() {
        val download = mutableState.value.download ?: return
        if (download.phase == DownloadPhase.Paused && downloading?.isActive != true) {
            fetch(download.model, startsListening = false)
        }
    }

    private fun choose(model: VoiceModel) {
        if (mutableState.value.chosen == model) return
        mutableState.update { it.copy(chosen = model) }
        core.dispatch(UserIntent.SetVoiceModel(model))
    }

    private fun fetch(model: VoiceModel, startsListening: Boolean) {
        downloading?.cancel()
        val files = model.files(Detector.Silero)
        val total = files.sumOf { it.bytes }
        val before = mutableState.value.download?.takeIf { it.model == model }?.done ?: 0L
        setDownload(
            VoiceDownload(model, before, total, DownloadPhase.Running, null, startsListening)
        )
        val cleaning = discarding
        downloading =
            scope.launch(ui) {
                cleaning?.join()
                try {
                    var shownAt = 0L
                    store.download(files) { done, _ ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - shownAt >= PROGRESS_MILLIS || done == total) {
                            shownAt = now
                            mutableState.update { state ->
                                val download = state.download
                                if (download?.model == model) {
                                    state.copy(download = download.copy(done = done))
                                } else {
                                    state
                                }
                            }
                        }
                    }
                    downloaded(model)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: DownloadFailure.NotEnoughSpace) {
                    failed(
                        model,
                        DownloadProblem.NotEnoughSpace(
                            failure.neededBytes,
                            roomFrom(model, failure),
                        ),
                    )
                } catch (failure: DownloadFailure.Damaged) {
                    Log.w(TAG, "A model file was damaged", failure)
                    failed(model, DownloadProblem.Damaged)
                } catch (failure: IOException) {
                    Log.w(TAG, "The model download failed", failure)
                    failed(
                        model,
                        if (isOnline()) DownloadProblem.Failed else DownloadProblem.NoConnection,
                    )
                }
            }
    }

    /** The installed model, when removing it would make room for [model]. */
    private fun roomFrom(model: VoiceModel, failure: DownloadFailure.NotEnoughSpace): VoiceModel? {
        val installed = mutableState.value.installed ?: return null
        if (installed == model) return null
        return installed.takeIf { failure.freeBytes + it.parakeet.bytes >= failure.neededBytes }
    }

    private fun failed(model: VoiceModel, problem: DownloadProblem) {
        downloading = null
        val download = mutableState.value.download ?: return
        if (download.model != model) return
        setDownload(
            download.copy(phase = DownloadPhase.Failed, problem = problem, startsListening = false)
        )
    }

    private fun downloaded(model: VoiceModel) {
        downloading = null
        val before = mutableState.value
        val startsListening = before.download?.startsListening == true
        mutableState.update { it.copy(installed = model, download = null) }
        val old = before.installed
        if (old != null && old != model) {
            if (session?.model == old) retiring = old else deleteModel(old)
        }
        if (startsListening && isShown && session == null) start()
    }

    /**
     * Stops any download of [model] and deletes what it fetched, unless it's the model in use. The
     * speech detector stays: both models use it.
     */
    private fun discardDownload(model: VoiceModel) {
        val running = downloading
        downloading = null
        mutableState.update { it.copy(download = null) }
        if (model == mutableState.value.installed) {
            running?.cancel()
            return
        }
        val before = discarding
        discarding =
            scope.launch(ui) {
                before?.join()
                running?.cancelAndJoin()
                withContext(Dispatchers.IO) { store.delete(model.parakeet.all) }
            }
    }

    private fun deleteModel(model: VoiceModel) {
        scope.launch(Dispatchers.IO) { store.delete(model.parakeet.all) }
    }

    // Small things

    private fun setDownload(download: VoiceDownload?) = mutableState.update {
        it.copy(download = download)
    }

    private fun setListening(change: (VoiceListening) -> VoiceListening) =
        mutableState.update { state ->
            state.listening?.let { state.copy(listening = change(it)) } ?: state
        }

    private fun notice(notice: VoiceNotice) = mutableState.update { it.copy(notice = notice) }

    private fun onUi(block: () -> Unit) {
        scope.launch(ui) { block() }
    }

    private fun isGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** TalkBack reads replies aloud, so listening takes the call path, which cancels the echo. */
    private fun isTalkBackOn(): Boolean =
        context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled

    private fun isOnline(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return false
        return connectivity
            .getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun freeBytes(): Long {
        folder.mkdirs()
        val storage = context.getSystemService(StorageManager::class.java)
        return storage.getAllocatableBytes(storage.getUuidForPath(folder))
    }

    private companion object {
        const val TAG = "Voice"
        const val GIB = 1L shl 30

        /** Voice at all: 5 GiB reported, which covers 6 GB phones. */
        const val VOICE_MEMORY_BYTES = 5 * GIB

        /** Full precision: 10.5 GiB reported, which covers 12 GB phones. */
        const val FULL_MEMORY_BYTES = 21 * GIB / 2

        const val METER_LEVELS = 18
        const val TICK_MILLIS = 1_000L
        const val PROGRESS_MILLIS = 250L

        /** A sent turn that hasn't started streaming by then was refused or already answered. */
        const val SEND_GRACE_MILLIS = 3_000L

        /** 15 minutes without speech while nothing plays ends listening. */
        const val QUIET_MILLIS = 15 * 60 * 1_000L

        /** A model that hasn't loaded by then failed, as when its process died before it said. */
        const val READY_WAIT_MILLIS = 90_000L

        fun isPlaying(state: AppState): Boolean =
            state.run.phase != RunPhase.Idle || state.autopilot.status != AutopilotStatus.Off
    }
}

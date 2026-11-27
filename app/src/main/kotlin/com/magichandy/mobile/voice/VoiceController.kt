package com.magichandy.mobile.voice

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Message
import android.os.RemoteException
import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.MicSource
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StartupState
import com.magichandy.mobile.core.api.VoiceConfig
import com.magichandy.mobile.core.api.VoiceControls
import com.magichandy.mobile.core.api.VoiceEnd
import com.magichandy.mobile.core.api.VoiceListening
import com.magichandy.mobile.core.api.VoiceMeter
import com.magichandy.mobile.core.api.VoiceModel
import com.magichandy.mobile.core.api.VoiceNotice
import com.magichandy.mobile.core.api.VoiceState
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    modelStore: ModelStore? = null,
) : VoiceControls {
    private val folder = VoiceService.modelFolder(context)
    private val store = modelStore ?: ModelStore(folder, freeBytes = ::freeBytes)
    private val tuning = VoiceTuning(context)
    private val ui = Dispatchers.Main.immediate
    private val mutableState: MutableStateFlow<VoiceState>
    private val mutableMeter = MutableStateFlow(VoiceMeter())

    override val state: StateFlow<VoiceState>
    override val meter: StateFlow<VoiceMeter> = mutableMeter.asStateFlow()

    /** The listening session, from the tap until it ends. */
    private class Session(
        val id: Int,
        val epoch: Long,
        val model: VoiceModel,
        val startedAt: Long,
        val turns: VoiceTurns,
    )

    private var session: Session? = null
    private var sessions = 0

    /** The current session's id, for the link's thread; 0 when not listening. */
    private val current = AtomicInteger(0)
    private val lastHeard = AtomicLong(0)
    private var lastPlaying = 0L

    private class VoiceProcess(val binder: IBinder, val death: IBinder.DeathRecipient)

    private var voiceProcess: VoiceProcess? = null

    private var ticking: Job? = null
    private var isShown = false

    private data class StartRequest(val model: VoiceModel, val epoch: Long)

    private var pendingStart: StartRequest? = null
    private var detectorJob: Job? = null
    override val stopEpoch: Long
        get() = core.currentStopEpoch

    private val downloads: VoiceDownloads
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
                    storage = voiceModelStorage(),
                )
            )
        downloads =
            VoiceDownloads(
                context,
                core,
                scope,
                mutableState,
                store,
                { session?.model },
                ::modelReady,
            )
        state = mutableState.asStateFlow()
        scope.launch(ui) { core.state.collect { observe(it) } }
    }

    // The app

    fun appShown() = onUi {
        isShown = true
        downloads.appShown()
    }

    fun appHidden() = onUi {
        isShown = false
        revokeStart()
        downloads.appHidden()
    }

    override fun start() = onUi {
        val model = state.value.installed ?: return@onUi
        if (session != null || !isShown) return@onUi
        val request = StartRequest(model, stopEpoch)
        if (pendingStart != request) {
            revokeStart()
            pendingStart = request
        }
        modelReady(model)
    }

    private fun revokeStart() {
        pendingStart = null
        detectorJob?.cancel()
        detectorJob = null
        downloads.disarm()
    }

    private fun modelReady(model: VoiceModel) {
        val request = pendingStart ?: return
        if (request.model != model || request.epoch != stopEpoch || !isShown || session != null)
            return
        if (!isGranted()) {
            revokeStart()
            notice(VoiceNotice(VoiceEnd.MicDenied, model))
            return
        }
        val config = tuning.read()
        if (!store.has(config.detector.model)) {
            if (detectorJob?.isActive != true)
                detectorJob =
                    scope.launch(ui) {
                        try {
                            store.download(listOf(config.detector.model)) { _, _ -> }
                            if (pendingStart === request) modelReady(model)
                        } catch (failure: IOException) {
                            currentCoroutineContext().ensureActive()
                            if (pendingStart === request) {
                                pendingStart = null
                                notice(VoiceNotice(VoiceEnd.ModelMissing, model))
                            }
                        }
                    }
            return
        }
        pendingStart = null
        listen(request, if (isTalkBackOn()) config.copy(source = MicSource.EchoCancel) else config)
    }

    override fun end() = onUi {
        revokeStart()
        endListening(VoiceEnd.User)
    }

    override fun download(model: VoiceModel, andStart: Boolean) = onUi {
        revokeStart()
        if (andStart && isShown) pendingStart = StartRequest(model, stopEpoch)
        downloads.download(model, andStart && pendingStart != null)
    }

    override fun cancelDownload() = onUi {
        revokeStart()
        downloads.cancelDownload()
    }

    override fun retryDownload() = onUi { downloads.retryDownload() }

    override fun remove() = onUi {
        revokeStart()
        endListening(VoiceEnd.User)
        downloads.remove()
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
                if (binder != null) onUi { if (session?.id == id) watch(id, binder) }
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

    private fun watch(id: Int, binder: IBinder) {
        if (voiceProcess?.binder === binder) return
        unwatch()
        val death = IBinder.DeathRecipient {
            onUi {
                if (session?.id == id && voiceProcess?.binder === binder) {
                    voiceProcess = null
                    endListening(VoiceEnd.ModelFailed)
                }
            }
        }
        voiceProcess = VoiceProcess(binder, death)
        try {
            binder.linkToDeath(death, 0)
        } catch (_: RemoteException) {
            voiceProcess = null
            endListening(VoiceEnd.ModelFailed)
        }
    }

    private fun unwatch() {
        val previous = voiceProcess ?: return
        voiceProcess = null
        previous.binder.unlinkToDeath(previous.death, 0)
    }

    // Listening

    private fun listen(request: StartRequest, config: VoiceConfig) {
        if (stopEpoch != request.epoch) return
        val model = request.model
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
        session =
            Session(
                id,
                request.epoch,
                model,
                now,
                VoiceTurns(core, request.epoch, ::setListening) {
                    endListening(VoiceEnd.Overloaded)
                },
            )
        current.set(id)
        lastHeard.set(now)
        core.setVoiceActive(true)
        mutableMeter.value = VoiceMeter()
        mutableState.update {
            it.copy(listening = VoiceListening(model), notice = null, endedByStop = null)
        }
        if (stopEpoch != request.epoch) {
            endListening(VoiceEnd.Stop)
            return
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
        unwatch()
        current.set(0)
        ticking?.cancel()
        ticking = null
        context.stopService(Intent(context, VoiceService::class.java))
        core.setVoiceActive(false)
        mutableMeter.value = VoiceMeter()
        if (reason == VoiceEnd.Stop) stoppedUnder = ended.epoch
        val notice =
            when (reason) {
                VoiceEnd.User,
                VoiceEnd.Stop -> null
                else -> VoiceNotice(reason, ended.model)
            }
        mutableState.update { it.copy(listening = null, notice = notice) }
        downloads.listeningEnded()
    }

    /** A turn from session [id]. */
    private fun heard(id: Int, text: String) {
        val listening = session ?: return
        val now = core.state.value
        // The Stop fence: nothing heard before a Stop reaches chat.
        if (listening.id != id || stopEpoch != listening.epoch) return
        lastHeard.set(SystemClock.elapsedRealtime())
        listening.turns.heard(text, now)
    }

    /** Follows the core: Stops, the chosen model, the reply, and whether anything plays. */
    private fun observe(now: AppState) {
        val listening = session
        if (listening != null && stopEpoch != listening.epoch) endListening(VoiceEnd.Stop)
        stoppedUnder?.let { epoch ->
            if (now.stopEpoch > epoch) {
                stoppedUnder = null
                mutableState.update { it.copy(endedByStop = now.stopEpoch) }
            }
        }
        if (pendingStart?.let { it.epoch != stopEpoch } == true) revokeStart()
        if (now.startup == StartupState.Ready) downloads.observe(now.app.voiceModel)
        if (isPlaying(now)) lastPlaying = SystemClock.elapsedRealtime()
        session?.turns?.observe(now)
    }

    /** Every few seconds: sends turns a quick reply left waiting, and ends 15 quiet minutes. */
    private fun tick() {
        val listening = session ?: return
        val now = core.state.value
        if (stopEpoch != listening.epoch) {
            endListening(VoiceEnd.Stop)
            return
        }
        listening.turns.observe(now)
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

    // Small things

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

        /** 15 minutes without speech while nothing plays ends listening. */
        const val QUIET_MILLIS = 15 * 60 * 1_000L

        /** A model that hasn't loaded by then failed, as when its process died before it said. */
        const val READY_WAIT_MILLIS = 90_000L

        fun isPlaying(state: AppState): Boolean =
            state.run.phase == RunPhase.Playing || state.run.phase == RunPhase.Edging
    }
}

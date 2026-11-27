package com.magichandy.mobile.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.magichandy.mobile.core.api.Detector
import com.magichandy.mobile.core.api.DownloadPhase
import com.magichandy.mobile.core.api.DownloadProblem
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.api.VoiceDownload
import com.magichandy.mobile.core.api.VoiceModel
import com.magichandy.mobile.core.api.VoiceState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns model installation, pause/resume and retirement. User commands run on the main lane. */
internal class VoiceDownloads(
    private val context: Context,
    private val core: MagicHandyCore,
    private val scope: CoroutineScope,
    private val mutableState: MutableStateFlow<VoiceState>,
    private val store: ModelStore,
    private val listeningModel: () -> VoiceModel?,
    private val ready: (VoiceModel) -> Unit,
) {
    private val ui = Dispatchers.Main.immediate
    private var downloading: Job? = null
    private var discarding: Job? = null
    private var retiring: VoiceModel? = null
    private var isShown = false
    private var isSettled = false
    private var coreChoice: VoiceModel? = null
    private var changed = 0L
    @Volatile private var transferGeneration = 0L

    fun observe(choice: VoiceModel) {
        if (!isSettled) {
            isSettled = true
            coreChoice = choice
            settle(choice)
        } else if (choice != coreChoice) {
            coreChoice = choice
            mutableState.update { it.copy(chosen = choice) }
        }
    }

    fun disarm() {
        mutableState.update { state ->
            val download = state.download
            if (download?.startsListening == true)
                state.copy(download = download.copy(startsListening = false))
            else state
        }
    }

    fun listeningEnded() {
        retiring?.let { old ->
            retiring = null
            if (old != mutableState.value.installed) deleteModel(old)
        }
    }

    /** The app came on screen: a paused download resumes. */
    fun appShown() {
        isShown = true
        if (isSettled) resumePaused()
    }

    /** The app left the screen: a download pauses, and won't start listening when it finishes. */
    fun appHidden() {
        isShown = false
        val download = mutableState.value.download ?: return
        if (download.phase == DownloadPhase.Running) {
            transferGeneration++
            downloading?.cancel()
            setDownload(download.copy(phase = DownloadPhase.Paused, startsListening = false))
        }
    }

    fun download(model: VoiceModel, andStart: Boolean) {
        changed++
        val now = mutableState.value
        choose(model)
        val download = now.download
        if (download != null && download.model != model) discardDownload(download.model)
        when {
            now.installed == model -> if (andStart) ready(model) else Unit
            download?.model == model && downloading?.isActive == true ->
                setDownload(download.copy(startsListening = andStart && isShown))
            else -> fetch(model, startsListening = andStart && isShown)
        }
    }

    fun cancelDownload() {
        val download = mutableState.value.download ?: return
        discardDownload(download.model)
        choose(mutableState.value.installed ?: VoiceModel.Compact)
    }

    fun retryDownload() {
        val download = mutableState.value.download ?: return
        fetch(
            download.model,
            startsListening = download.startsListening && isShown,
        )
    }

    fun remove() {
        changed++
        transferGeneration++
        downloading?.cancel()
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
                store.delete(
                    VoiceModel.entries.flatMap { it.parakeet.all } +
                        Detector.entries.map { it.model }
                )
            }
    }

    /**
     * Once the settings have loaded: finds the model on the phone and removes any other, in case
     * the app stopped mid-switch. A download it finds part done resumes while the app is on screen.
     */
    private fun settle(chosen: VoiceModel) {
        val generation = changed
        discarding =
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
                if (generation != changed) return@launch
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
        val attempt = ++transferGeneration
        val previous = downloading
        previous?.cancel()
        val files = model.files(Detector.Silero)
        val total = files.sumOf { it.bytes }
        val before = mutableState.value.download?.takeIf { it.model == model }?.done ?: 0L
        setDownload(
            VoiceDownload(model, before, total, DownloadPhase.Running, null, startsListening)
        )
        val cleaning = discarding
        downloading =
            scope.launch(ui) {
                previous?.join()
                cleaning?.join()
                try {
                    var shownAt = 0L
                    store.download(files) { done, _ ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - shownAt >= PROGRESS_MILLIS || done == total) {
                            shownAt = now
                            mutableState.update { state ->
                                val download = state.download
                                if (attempt == transferGeneration && download?.model == model) {
                                    state.copy(download = download.copy(done = done))
                                } else {
                                    state
                                }
                            }
                        }
                    }
                    if (attempt == transferGeneration) downloaded(model)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: DownloadFailure.NotEnoughSpace) {
                    if (attempt != transferGeneration) return@launch
                    failed(
                        model,
                        DownloadProblem.NotEnoughSpace(
                            failure.neededBytes,
                            roomFrom(model, failure),
                        ),
                    )
                } catch (failure: DownloadFailure.Damaged) {
                    if (attempt != transferGeneration) return@launch
                    Log.w(TAG, "A model file was damaged", failure)
                    failed(model, DownloadProblem.Damaged)
                } catch (failure: IOException) {
                    if (attempt != transferGeneration) return@launch
                    currentCoroutineContext().ensureActive()
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
        setDownload(download.copy(phase = DownloadPhase.Failed, problem = problem))
    }

    private fun downloaded(model: VoiceModel) {
        downloading = null
        val before = mutableState.value
        val startsListening = before.download?.startsListening == true
        mutableState.update { it.copy(installed = model, download = null) }
        val old = before.installed
        if (old != null && old != model) {
            if (listeningModel() == old) retiring = old else deleteModel(old)
        }
        if (startsListening && isShown && listeningModel() == null) ready(model)
    }

    /**
     * Stops any download of [model] and deletes what it fetched, unless it's the model in use. The
     * speech detector stays: both models use it.
     */
    private fun discardDownload(model: VoiceModel) {
        transferGeneration++
        downloading?.cancel()
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
        val previous = discarding
        discarding =
            scope.launch(ui) {
                previous?.join()
                store.delete(model.parakeet.all)
            }
    }

    private fun setDownload(download: VoiceDownload?) = mutableState.update {
        it.copy(download = download)
    }

    private fun isOnline(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return false
        return connectivity
            .getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private companion object {
        const val TAG = "Voice"
        const val PROGRESS_MILLIS = 250L
    }
}

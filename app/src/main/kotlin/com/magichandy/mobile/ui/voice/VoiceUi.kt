package com.magichandy.mobile.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.DownloadProblem
import com.magichandy.mobile.core.api.VoiceControls
import com.magichandy.mobile.core.api.VoiceEnd
import com.magichandy.mobile.core.api.VoiceMeter
import com.magichandy.mobile.core.api.VoiceModel
import com.magichandy.mobile.core.api.VoiceNotice
import com.magichandy.mobile.core.api.VoiceState
import kotlinx.coroutines.flow.StateFlow

/**
 * Voice input as the screens see it (docs/voice-design.md §4, §5): its state, its meter and what
 * they can ask of it.
 *
 * @property isLanguageSupported false in Chinese and Japanese, where spoken Stop doesn't work, so
 *   voice isn't offered; Remove stays.
 * @property withMicrophone runs its argument once the microphone is allowed, asking Android first
 *   when it isn't.
 */
@Stable
class VoiceUi(
    val state: VoiceState,
    val meter: StateFlow<VoiceMeter>,
    val controls: VoiceControls,
    val isLanguageSupported: Boolean,
    val withMicrophone: (then: () -> Unit) -> Unit,
    val openSetup: () -> Unit,
    val openPicker: () -> Unit,
    val openModelSettings: () -> Unit,
    val openSettings: () -> Unit,
    val openMicrophoneSettings: () -> Unit,
) {
    /** True when Chat offers the microphone. */
    val isOffered: Boolean
        get() = state.isOffered && isLanguageSupported

    /** True when App settings shows Voice: it's offered, or a model is on the phone to remove. */
    val hasSettings: Boolean
        get() = isOffered || state.installed != null || state.download != null

    companion object {
        /** Voice that isn't offered, for previews of screens that don't show it. */
        val None: VoiceUi = of(VoiceControls.Fixed(), VoiceState())

        /** A fixed [state] with nothing behind it, for previews. */
        fun of(controls: VoiceControls, state: VoiceState = controls.state.value): VoiceUi =
            VoiceUi(state, controls.meter, controls, true, { it() }, {}, {}, {}, {}, {})
    }
}

/**
 * [controls] for the screens, with the microphone request and the pages voice opens.
 *
 * @param openSetup opens the first-use sheet (V-VoiceSetup).
 * @param openPicker opens the first-use picker over it (V-VoiceModelPick).
 * @param openModelSettings opens the picker in its Settings form (V-VoiceModelSwitch).
 * @param openSettings opens Settings › Voice.
 */
@Composable
fun rememberVoiceUi(
    controls: VoiceControls,
    openSetup: () -> Unit,
    openPicker: () -> Unit,
    openModelSettings: () -> Unit,
    openSettings: () -> Unit,
): VoiceUi {
    val state by controls.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    var waiting by remember { mutableStateOf<(() -> Unit)?>(null) }
    val microphone =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val then = waiting
            waiting = null
            if (granted) then?.invoke() else controls.microphoneRefused()
        }
    return VoiceUi(
        state = state,
        meter = controls.meter,
        controls = controls,
        isLanguageSupported = language !in unsupportedLanguages,
        withMicrophone = { then ->
            if (isMicrophoneAllowed(context)) {
                then()
            } else {
                val requestedEpoch = controls.stopEpoch
                waiting = { if (controls.stopEpoch == requestedEpoch) then() }
                microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        openSetup = openSetup,
        openPicker = openPicker,
        openModelSettings = openModelSettings,
        openSettings = openSettings,
        openMicrophoneSettings = { openAppDetails(context) },
    )
}

/** Spoken Stop works in English, Spanish and Portuguese, so voice isn't offered in these. */
private val unsupportedLanguages = setOf("zh", "ja")

fun isMicrophoneAllowed(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/** Android's page for this app, where its microphone access is. */
private fun openAppDetails(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
internal fun modelName(model: VoiceModel): String =
    stringResource(
        when (model) {
            VoiceModel.Compact -> R.string.voice_model_compact
            VoiceModel.Full -> R.string.voice_model_full
        }
    )

/** What [model] downloads, with the speech detector. */
internal fun VoiceUi.downloadBytes(model: VoiceModel): Long =
    state.storage[model]?.downloadBytes ?: 0L

/** [bytes] as the phone shows file sizes, such as "671 MB". */
@Composable
internal fun fileSize(bytes: Long): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes)

/** The space a download of [model] needs: itself and what it leaves free. */
internal fun VoiceUi.neededBytes(model: VoiceModel): Long =
    state.storage[model]?.requiredFreeBytes ?: 0L

@Composable
internal fun problemText(problem: DownloadProblem): String =
    when (problem) {
        DownloadProblem.NoConnection -> stringResource(R.string.voice_problem_no_connection)
        is DownloadProblem.NotEnoughSpace -> {
            val size = fileSize(problem.neededBytes)
            val removing = problem.removing
            if (removing == null) stringResource(R.string.voice_problem_space, size)
            else stringResource(R.string.voice_problem_space_remove, size, modelName(removing))
        }
        DownloadProblem.Damaged -> stringResource(R.string.voice_problem_damaged)
        DownloadProblem.Failed -> stringResource(R.string.voice_problem_failed)
    }

/** The next step a notice offers (docs/voice-design.md §4). */
internal enum class NoticeStep {
    StartAgain,
    OpenSettings,
    DownloadAgain,
    SwitchToCompact,
}

internal fun noticeStep(notice: VoiceNotice): NoticeStep =
    when (notice.reason) {
        VoiceEnd.MicDenied -> NoticeStep.OpenSettings
        VoiceEnd.ModelMissing -> NoticeStep.DownloadAgain
        VoiceEnd.ModelFailed,
        VoiceEnd.Overloaded ->
            if (notice.model == VoiceModel.Full) NoticeStep.SwitchToCompact
            else NoticeStep.StartAgain
        else -> NoticeStep.StartAgain
    }

@Composable
internal fun noticeText(notice: VoiceNotice): String =
    stringResource(
        when (notice.reason) {
            VoiceEnd.Overloaded -> R.string.voice_ended_overloaded
            VoiceEnd.InputChanged -> R.string.voice_ended_input_changed
            VoiceEnd.Quiet -> R.string.voice_ended_quiet
            VoiceEnd.MicTaken -> R.string.voice_ended_mic_taken
            VoiceEnd.MicDenied -> R.string.voice_ended_mic_denied
            VoiceEnd.MicFailed -> R.string.voice_ended_mic_failed
            VoiceEnd.ModelMissing -> R.string.voice_ended_model_missing
            VoiceEnd.ModelFailed ->
                if (notice.model == VoiceModel.Full) R.string.voice_ended_full_failed
                else R.string.voice_ended_model_failed
            VoiceEnd.User,
            VoiceEnd.Stop -> R.string.voice_announce_ended
        }
    )

@Composable
internal fun stepLabel(step: NoticeStep): String =
    stringResource(
        when (step) {
            NoticeStep.StartAgain -> R.string.voice_start_again
            NoticeStep.OpenSettings -> R.string.voice_open_settings
            NoticeStep.DownloadAgain -> R.string.voice_download_again
            NoticeStep.SwitchToCompact -> R.string.voice_switch_compact
        }
    )

/** Takes the notice's next step. */
internal fun VoiceUi.take(step: NoticeStep, notice: VoiceNotice) {
    controls.dismissNotice()
    when (step) {
        NoticeStep.StartAgain -> withMicrophone(controls::start)
        NoticeStep.OpenSettings -> openMicrophoneSettings()
        NoticeStep.DownloadAgain -> {
            val model = notice.model ?: VoiceModel.Compact
            controls.remove()
            withMicrophone { controls.download(model, andStart = true) }
        }
        NoticeStep.SwitchToCompact ->
            withMicrophone { controls.download(VoiceModel.Compact, andStart = true) }
    }
}

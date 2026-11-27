package com.magichandy.mobile.ui.format

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AfterLossBehaviour
import com.magichandy.mobile.core.api.AppLanguage
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.CheckIns
import com.magichandy.mobile.core.api.CommandReceipt
import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.ControlKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.LimitKind
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.MotionSource
import com.magichandy.mobile.core.api.MotionStyle
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.ui.components.DotTone
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Instant

/** Localized words for contract values. Screens never spell these out themselves. */
object Words {
    @Composable
    fun family(family: DeviceFamily): String =
        stringResource(
            when (family) {
                DeviceFamily.LinearActuator -> R.string.family_linear_actuator
                DeviceFamily.Handy -> R.string.family_handy
                DeviceFamily.Vibrator -> R.string.family_vibrator
                DeviceFamily.Coyote -> R.string.family_coyote
                DeviceFamily.AutoblowUltra -> R.string.family_autoblow_ultra
                DeviceFamily.VacuGlide -> R.string.family_vacuglide
            }
        )

    @Composable
    fun connection(kind: ConnectionKind): String =
        stringResource(
            when (kind) {
                ConnectionKind.Bluetooth -> R.string.connection_bluetooth
                ConnectionKind.HandyCloud -> R.string.connection_handy_cloud
                ConnectionKind.AutoblowCloud -> R.string.connection_autoblow_cloud
                ConnectionKind.IntifaceOnPhone -> R.string.connection_intiface_phone
                ConnectionKind.IntifaceOnNetwork -> R.string.connection_intiface_network
            }
        )

    @Composable
    fun status(status: DeviceStatus): String =
        stringResource(
            when (status) {
                DeviceStatus.NeedsSetup -> R.string.status_needs_setup
                DeviceStatus.NotConnected -> R.string.status_not_connected
                DeviceStatus.Connecting -> R.string.status_connecting
                DeviceStatus.Connected -> R.string.status_connected
                DeviceStatus.Playing -> R.string.status_playing
                DeviceStatus.Paused -> R.string.status_paused
                DeviceStatus.Lost -> R.string.status_lost
            }
        )

    /** The dot for a device status (system design §3.1). */
    fun statusTone(status: DeviceStatus): DotTone =
        when (status) {
            DeviceStatus.NeedsSetup -> DotTone.Ring
            DeviceStatus.NotConnected -> DotTone.Faint
            DeviceStatus.Connecting -> DotTone.Warn
            DeviceStatus.Connected,
            DeviceStatus.Playing -> DotTone.Ok
            DeviceStatus.Paused -> DotTone.Idle
            DeviceStatus.Lost -> DotTone.Danger
        }

    @Composable
    fun motionMode(mode: MotionMode): String =
        stringResource(
            when (mode) {
                MotionMode.Layered -> R.string.mode_layered
                MotionMode.Simple -> R.string.mode_simple
                MotionMode.Tempo -> R.string.mode_tempo
                MotionMode.Off -> R.string.mode_off
            }
        )

    @Composable
    fun motionModeDescription(mode: MotionMode): String =
        stringResource(
            when (mode) {
                MotionMode.Layered -> R.string.mode_layered_desc
                MotionMode.Simple -> R.string.mode_simple_desc
                MotionMode.Tempo -> R.string.mode_tempo_desc
                MotionMode.Off -> R.string.mode_off_desc
            }
        )

    @Composable
    fun style(style: MotionStyle): String =
        stringResource(
            when (style) {
                MotionStyle.Gentle -> R.string.style_gentle
                MotionStyle.Balanced -> R.string.style_balanced
                MotionStyle.Intense -> R.string.style_intense
            }
        )

    @Composable
    fun capability(level: CapabilityLevel): String =
        stringResource(
            when (level) {
                CapabilityLevel.Simple -> R.string.capability_simple
                CapabilityLevel.Intermediate -> R.string.capability_intermediate
                CapabilityLevel.Advanced -> R.string.capability_advanced
            }
        )

    @Composable
    fun checkIns(checkIns: CheckIns): String =
        stringResource(
            when (checkIns) {
                CheckIns.Off -> R.string.check_ins_off
                CheckIns.Talk -> R.string.check_ins_talk
                CheckIns.TalkAndAdjust -> R.string.check_ins_talk_adjust
            }
        )

    @Composable
    fun limit(kind: LimitKind): String =
        stringResource(
            when (kind) {
                LimitKind.TopSpeed -> R.string.limit_top_speed
                LimitKind.StrokeLow -> R.string.limit_stroke_low
                LimitKind.StrokeHigh -> R.string.limit_stroke_high
            }
        )

    @Composable
    fun limitInline(kind: LimitKind): String =
        stringResource(
            when (kind) {
                LimitKind.TopSpeed -> R.string.limit_top_speed_inline
                LimitKind.StrokeLow -> R.string.limit_stroke_low_inline
                LimitKind.StrokeHigh -> R.string.limit_stroke_high_inline
            }
        )

    @Composable
    fun source(source: MotionSource): String =
        stringResource(
            when (source) {
                MotionSource.You -> R.string.source_you
                MotionSource.Chat -> R.string.source_chat
                MotionSource.Autopilot -> R.string.source_autopilot
            }
        )

    /** Why the core refused a form, in words the person can act on. */
    @Composable
    fun formReason(reason: FormReason): String =
        stringResource(
            when (reason) {
                FormReason.InvalidKey -> R.string.form_invalid_key
                FormReason.InvalidAddress -> R.string.form_invalid_address
                FormReason.InvalidName -> R.string.form_invalid_name
                FormReason.InvalidModel -> R.string.form_invalid_model
                FormReason.Unsupported -> R.string.form_unsupported
                FormReason.IncompleteModelReply -> R.string.server_failure_incomplete
                FormReason.ConsentRequired -> R.string.form_consent_required
                FormReason.Unreachable -> R.string.form_unreachable
                FormReason.ProviderRefused -> R.string.form_provider_refused
                FormReason.Duplicate -> R.string.form_duplicate
                FormReason.AmbiguousDevice -> R.string.form_ambiguous_device
                FormReason.ChooseDevice -> R.string.form_choose_device
                FormReason.NotFound -> R.string.form_not_found
                FormReason.RunInProgress -> R.string.form_run_in_progress
                FormReason.ReconcileDevice -> R.string.form_reconcile_device
                FormReason.Busy -> R.string.form_busy
                FormReason.StorageUnavailable -> R.string.form_storage_unavailable
                FormReason.TooLong -> R.string.form_too_long
                FormReason.LimitReached -> R.string.form_limit_reached
            }
        )

    /** What each palette is for, under the palette choice in App settings. */
    @Composable
    fun paletteUse(palette: Palette): String =
        stringResource(
            when (palette) {
                Palette.SteelAzure -> R.string.palette_steel_azure_use
                Palette.OledBlack,
                Palette.Lowlight -> R.string.palette_oled_black_use
                Palette.HighContrast -> R.string.palette_high_contrast_use
            }
        )

    @Composable
    fun palette(palette: Palette): String =
        stringResource(
            when (palette) {
                Palette.SteelAzure -> R.string.palette_steel_azure
                Palette.OledBlack,
                Palette.Lowlight -> R.string.palette_oled_black
                Palette.HighContrast -> R.string.palette_high_contrast
            }
        )

    @Composable
    fun language(language: AppLanguage): String =
        stringResource(
            when (language) {
                AppLanguage.System -> R.string.language_system
                AppLanguage.English -> R.string.language_english
                AppLanguage.Spanish -> R.string.language_spanish
                AppLanguage.PortugueseBrazil -> R.string.language_portuguese_brazil
                AppLanguage.Japanese -> R.string.language_japanese
                AppLanguage.ChineseSimplified -> R.string.language_chinese_simplified
            }
        )

    @Composable
    fun provider(provider: ProviderKind): String =
        stringResource(
            when (provider) {
                ProviderKind.ChatGpt -> R.string.provider_chatgpt
                ProviderKind.OpenRouter -> R.string.provider_openrouter
                ProviderKind.Ollama -> R.string.provider_ollama
                ProviderKind.LlamaCpp -> R.string.provider_llamacpp
                ProviderKind.OpenAiCompatible -> R.string.provider_openai_compatible
            }
        )

    @Composable fun percent(value: Percent): String = stringResource(R.string.percent, value.value)

    @Composable fun percent(value: Int): String = stringResource(R.string.percent, value)

    @Composable
    fun range(range: IntRange): String = stringResource(R.string.range, range.first, range.last)

    @Composable
    fun minutes(duration: Duration): String =
        stringResource(R.string.minutes_short, duration.inWholeMinutes.toInt())

    @Composable
    fun milliseconds(duration: Duration): String =
        stringResource(R.string.milliseconds_short, duration.inWholeMilliseconds.toInt())

    /** A short duration as seconds with one decimal, such as "1.9 s". */
    @Composable
    fun seconds(duration: Duration): String =
        stringResource(R.string.seconds_short, "%.1f".format(duration.inWholeMilliseconds / 1000.0))

    /** A countdown as minutes and seconds, such as "0:40". */
    fun countdown(duration: Duration): String {
        val total = duration.inWholeSeconds.coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    /**
     * A wall-clock time in the phone's own format, such as "21:40": the app's language, and the
     * phone's 12- or 24-hour setting. Both can change while the app runs.
     */
    @Composable
    fun clockTime(instant: Instant): String {
        val locale = LocalConfiguration.current.locales[0]
        val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
        val formatter =
            remember(locale, is24Hour) {
                val skeleton = if (is24Hour) "Hm" else "hm"
                DateTimeFormatter.ofPattern(
                    DateFormat.getBestDateTimePattern(locale, skeleton),
                    locale,
                )
            }
        return java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds())
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    }

    /** What a reply commanded: "Pace 62 → 48 · Stroke 15–85 · commanded". */
    @Composable
    fun receipt(receipt: CommandReceipt): String {
        val parts = mutableListOf<String>()
        val byControl = receipt.changes.associateBy { it.control }
        byControl[ControlKind.Pace]?.let { change ->
            val from = change.from
            parts +=
                if (from != null && from != change.to) {
                    stringResource(R.string.receipt_pace_change, from, change.to)
                } else {
                    stringResource(R.string.receipt_pace, change.to)
                }
        }
        // Only what the reply commanded: an end it left alone isn't shown as if it had been set.
        val low = byControl[ControlKind.StrokeLow]
        val high = byControl[ControlKind.StrokeHigh]
        when {
            low != null && high != null ->
                parts += stringResource(R.string.receipt_stroke, low.to, high.to)
            low != null -> parts += stringResource(R.string.receipt_stroke_low, low.to)
            high != null -> parts += stringResource(R.string.receipt_stroke_high, high.to)
        }
        byControl[ControlKind.Level]?.let { parts += stringResource(R.string.receipt_level, it.to) }
        byControl[ControlKind.Tempo]?.let { parts += stringResource(R.string.receipt_tempo, it.to) }
        receipt.pattern?.let { pattern ->
            parts +=
                when {
                    pattern.groups.isEmpty() ->
                        stringResource(R.string.receipt_pattern_range, pattern.low, pattern.high)
                    pattern.groups.size == 1 && pattern.groups.single().count == 1 ->
                        pattern.groups.single().let { group ->
                            stringResource(R.string.receipt_sweeps, group.low, group.high)
                        }
                    else -> {
                        val phrase =
                            pattern.groups
                                .map { group ->
                                    stringResource(
                                        R.string.receipt_stroke_group,
                                        group.count,
                                        group.low,
                                        group.high,
                                    )
                                }
                                .joinToString(stringResource(R.string.separator))
                        stringResource(R.string.receipt_phrase, phrase)
                    }
                }
        }
        parts += stringResource(R.string.receipt_commanded)
        return parts.joinToString(stringResource(R.string.separator))
    }

    /** One sentence on what [name] does by itself when the app can't reach it. */
    @Composable
    fun afterLoss(behaviour: AfterLossBehaviour, name: String): String =
        stringResource(
            when (behaviour) {
                AfterLossBehaviour.PausesWhenBufferRunsOut -> R.string.after_loss_short_pauses
                AfterLossBehaviour.StopsWhenAppDisconnects -> R.string.after_loss_short_intiface
                AfterLossBehaviour.GoesSilent -> R.string.after_loss_short_silent
                AfterLossBehaviour.FinishesLastMove -> R.string.after_loss_short_last_move
                AfterLossBehaviour.KeepsLastSpeed -> R.string.after_loss_short_keeps
            },
            name,
        )
}

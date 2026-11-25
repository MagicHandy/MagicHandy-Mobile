// Ported from internal/config/{settings,settings_patch}.go at 9a4c36cf (PR #373).
package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.AppLanguage
import com.magichandy.mobile.core.api.ChangeFrequency
import com.magichandy.mobile.core.api.CheckIns
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.MotionStyle
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.VoiceModel
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

/** The last observation delivered by Android's tile lifecycle. */
public enum class TilePresence {
    Unknown,
    Added,
    Removed,
}

/** The mobile settings document. All Stop and motion evidence lives outside this document. */
@kotlinx.serialization.Serializable
public data class Preferences(
    public val adultsOnly: Boolean = false,
    public val coyoteNotes: Boolean = false,
    @kotlinx.serialization.Serializable(with = StoredPalette::class)
    public val palette: Palette = Palette.SteelAzure,
    public val language: AppLanguage = AppLanguage.System,
    public val mode: MotionMode = MotionMode.Layered,
    public val style: MotionStyle = MotionStyle.Balanced,
    public val memoryOn: Boolean = false,
    public val limits: Limits = Limits(Percent(80), Percent(0), Percent(100)),
    public val frequency: ChangeFrequency = ChangeFrequency(5),
    public val checkIns: CheckIns = CheckIns.Off,
    public val finishAfter: Duration? = null,
    public val askedNotifications: Boolean = false,
    public val refusalFallback: com.magichandy.mobile.core.api.ConnectionId? = null,
    public val stopTile: TilePresence = TilePresence.Unknown,
    /** The built-in settings edit independently of the custom roster rows. */
    public val builtInPersonality: Personality = Personality(),
    /** Stable ID selected for the next turn. */
    public val selectedPersona: PersonaId = builtInPersona.id,
    /** When decoding a legacy document, its existing adult acknowledgement completes setup. */
    public val isSetupDone: Boolean = adultsOnly,
    /** The speech model voice input uses or is switching to. */
    public val voiceModel: VoiceModel = VoiceModel.Compact,
)

/** A field carries its typed update function and stable persisted name. */
public class PreferenceField<T>
private constructor(public val name: String, internal val set: (Preferences, T) -> Preferences) {
    /** Create an edit touching this field alone. */
    public fun edit(value: T): PreferenceEdit = PreferenceEdit(name) { set(it, value) }

    /** Fields accepted by revisioned patches. */
    public companion object {
        /** Affects only the four editable fields of MagicHandy. */
        public val BuiltInPersonality: PreferenceField<Personality> =
            PreferenceField("built_in_personality") { s, v -> s.copy(builtInPersonality = v) }
        /** The next speaker. Changes after a turn starts preserve its original speaker. */
        public val SelectedPersona: PreferenceField<PersonaId> =
            PreferenceField("selected_persona") { s, v -> s.copy(selectedPersona = v) }
        public val StopTile: PreferenceField<TilePresence> =
            PreferenceField("stop_tile") { s, v -> s.copy(stopTile = v) }
        public val AskedNotifications: PreferenceField<Boolean> =
            PreferenceField("asked_notifications") { s, v -> s.copy(askedNotifications = v) }
        /** Explicit local role for one hosted refusal retry. */
        public val RefusalFallback: PreferenceField<com.magichandy.mobile.core.api.ConnectionId?> =
            PreferenceField("refusal_fallback") { s, v -> s.copy(refusalFallback = v) }
        /** The final step of the mobile setup flow. */
        public val SetupDone: PreferenceField<Boolean> =
            PreferenceField("setup_done") { s, v -> s.copy(isSetupDone = v) }
        /** The onboarding acknowledgement. */
        public val AdultsOnly: PreferenceField<Boolean> =
            PreferenceField("adults_only") { s, v -> s.copy(adultsOnly = v) }
        /** Consent to Coyote discovery. */
        public val CoyoteNotes: PreferenceField<Boolean> =
            PreferenceField("coyote_notes") { s, v -> s.copy(coyoteNotes = v) }
        /** Selected visual palette. */
        public val PaletteChoice: PreferenceField<Palette> =
            PreferenceField("palette") { s, v -> s.copy(palette = v.current()) }
        /** Selected UI language or system default. */
        public val Language: PreferenceField<AppLanguage> =
            PreferenceField("language") { s, v -> s.copy(language = v) }
        /** Model-facing motion mode. */
        public val Mode: PreferenceField<MotionMode> =
            PreferenceField("mode") { s, v -> s.copy(mode = v) }
        /** Motion shaping preference. */
        public val Style: PreferenceField<MotionStyle> =
            PreferenceField("style") { s, v -> s.copy(style = v) }
        /** Opt-in long-term memory. */
        public val Memory: PreferenceField<Boolean> =
            PreferenceField("memory") { s, v -> s.copy(memoryOn = v) }
        /** Manual output window and speed ceiling, changed together. */
        public val OutputLimits: PreferenceField<Limits> =
            PreferenceField("limits") { s, v -> s.copy(limits = v) }
        /** Planner timing preference, one through eight. */
        public val Frequency: PreferenceField<ChangeFrequency> =
            PreferenceField("change_frequency") { s, v -> s.copy(frequency = v) }
        /** Planner check-in preference. */
        public val CheckIn: PreferenceField<CheckIns> =
            PreferenceField("check_ins") { s, v -> s.copy(checkIns = v) }
        /** Optional planning target duration. */
        public val FinishAfter: PreferenceField<Duration?> =
            PreferenceField("finish_target") { s, v -> s.copy(finishAfter = v) }
        /** Voice input's speech model. */
        public val VoiceModelChoice: PreferenceField<VoiceModel> =
            PreferenceField("voice_model") { s, v -> s.copy(voiceModel = v) }
    }
}

/** One dirty field and its typed transformation, for the serialized settings repository. */
public class PreferenceEdit
internal constructor(public val field: String, public val apply: (Preferences) -> Preferences)

/** Last change revision per field allows disjoint stale patches to merge. */
@kotlinx.serialization.Serializable
public data class SettingsDocument(
    public val revision: Long = 0,
    public val values: Preferences = Preferences(),
    public val changedAt: Map<String, Long> = emptyMap(),
    /** Transactional projection of the separate Room rows, sorted by name. */
    @kotlinx.serialization.Transient public val customPersonas: List<PersonaRef> = emptyList(),
)

/** Atomically merge all dirty fields or report a conflict. */
public interface SettingsStore {
    /** The most recent revision and complete settings document. */
    public val state: StateFlow<SettingsDocument>

    /**
     * Apply one roster edit, its settings fields and any memory deletion in a single transaction.
     */
    public suspend fun editPersonas(edit: (SettingsDocument) -> PersonaChange): FormResult =
        FormResult.Rejected(FormReason.Unsupported)

    /** Reject future revisions and overlapping stale fields. No partial application. */
    public suspend fun patch(baseRevision: Long, edits: List<PreferenceEdit>): Boolean
}

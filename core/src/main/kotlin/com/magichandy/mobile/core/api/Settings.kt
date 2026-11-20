package com.magichandy.mobile.core.api

/**
 * The compact settings sheet's session choices.
 *
 * @property availableModes the modes the current lead allows: Layered, Simple and Off, or Tempo and
 *   Off when a VacuGlide leads.
 * @property memoryEntries saved memory entries for this persona, for review and clear.
 */
public data class SessionSettings(
    public val motionMode: MotionMode,
    public val availableModes: List<MotionMode>,
    public val style: MotionStyle,
    public val persona: PersonaRef,
    public val personas: List<PersonaRef>,
    public val isMemoryOn: Boolean,
    public val memoryEntries: Int,
    /** Saved entries for local review and clearing, newest first. */
    public val memories: List<String> = emptyList(),
)

/** What the AI may do with motion. Every mode works at every capability level. */
public enum class MotionMode {
    Layered,
    Simple,
    Tempo,
    Off,
}

/** Biases the character of motion within the same limits. It doesn't change the wording. */
public enum class MotionStyle {
    Gentle,
    Balanced,
    Intense,
}

/** A built-in persona preset. */
public data class PersonaRef(
    public val id: PersonaId,
    public val name: String,
    public val summary: String,
)

/** App-wide preferences, kept with the revisioned settings. */
public data class AppSettingsState(
    public val palette: Palette,
    public val language: AppLanguage,
    public val versionName: String,
    public val update: UpdateCheck,
    /**
     * Latest permission and battery state reported when Android brings the app to the foreground.
     */
    public val notificationsAllowed: Boolean = false,
    public val isBatteryUnrestricted: Boolean = false,
    /** Becomes true when Android has supplied the two system settings. */
    public val hasSystemAccessSnapshot: Boolean = false,
    /** True after the first connected-device notification permission request. */
    public val hasAskedForNotifications: Boolean = false,
    /** Last presence reported by Android. Null until System UI has reported this tile. */
    public val isStopTileAdded: Boolean? = null,
)

/**
 * The palettes, all dark and chosen for phones (plan §14.1). The semantic colours stay fixed in
 * each.
 */
public enum class Palette {
    /** The default, in the brand's Graphite greys. */
    SteelAzure,

    /** Retired on 2026-11-20 in favour of [OledBlack]. A saved Lowlight loads as [OledBlack]. */
    Lowlight,

    /** The brightest text and outlines, for low vision and bright light. */
    HighContrast,

    /** True black: OLED screens draw it with their pixels off, so it doesn't glow in the dark. */
    OledBlack,
}

/** The five UI languages, or the phone's own. */
public enum class AppLanguage {
    System,
    English,
    Spanish,
    PortugueseBrazil,
    Japanese,
    ChineseSimplified,
}

/** The user-run release check. There is no background polling. */
public sealed interface UpdateCheck {
    public data object NotChecked : UpdateCheck

    public data object Checking : UpdateCheck

    public data object UpToDate : UpdateCheck

    public data class Available(public val version: String) : UpdateCheck

    public data object Failed : UpdateCheck
}

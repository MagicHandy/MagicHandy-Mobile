package com.magichandy.mobile.core.api

import kotlin.time.Duration

/**
 * Saved devices, scanning and the radio and network state the Devices screen shows.
 *
 * @property devices every saved device, in the order the user arranged them.
 * @property hasAcknowledgedCoyoteNotes the Coyote's safety notes were read; scanning for a Coyote
 *   stays unavailable until then.
 */
public data class DevicesState(
    public val devices: List<DeviceSummary>,
    public val isBluetoothOn: Boolean,
    public val isOnline: Boolean,
    public val scan: ScanState,
    public val hasAcknowledgedCoyoteNotes: Boolean,
    /** Devices that still require a Stop on return or an explicit local check. */
    public val awaitingReturn: Set<DeviceId> = emptySet(),
)

/** A vibration-capable endpoint reported by the named Intiface server. */
public data class IntifaceChoice(
    public val deviceIndex: Int,
    public val name: String,
    public val selection: String? = null,
)

/** One LinearCmd feature. Indices belong only to the discovery that returned this choice. */
public data class IntifaceLinearChoice(
    public val deviceIndex: Int,
    public val actuatorIndex: Int,
    public val name: String,
    public val descriptor: String = "",
    /** Opaque identity to return with the form if discovery results change. */
    public val selection: String? = null,
)

/**
 * One saved device.
 *
 * @property name the user's name for it, such as "Bedroom Handy".
 * @property battery the device's reported battery level, when it reports one.
 * @property linkLatency the last measured round trip, for cloud devices.
 * @property output what the app commands this device now; null while it isn't playing.
 * @property afterLoss what the device does by itself if the app stops talking to it.
 */
public data class DeviceSummary(
    public val id: DeviceId,
    public val name: String,
    public val family: DeviceFamily,
    public val connection: ConnectionKind,
    public val status: DeviceStatus,
    public val membership: RunMembership,
    public val battery: Percent?,
    public val linkLatency: Duration?,
    public val output: DeviceOutput?,
    public val settings: DeviceSettings,
    public val afterLoss: AfterLossBehaviour,
    /** Why the last connection or read-only check failed. Cleared on a new attempt. */
    public val connectionFailure: DeviceConnectionFailure? = null,
) {
    /** A fresh Handy Cloud probe can replace the pending attempt while the device is booting. */
    public val canRetryWhileConnecting: Boolean
        get() = connection == ConnectionKind.HandyCloud && status == DeviceStatus.Connecting
}

/** The device families the desktop supports, all of which the phone supports (plan §8). */
public enum class DeviceFamily {
    Handy,
    Vibrator,
    Coyote,
    AutoblowUltra,
    VacuGlide,
    LinearActuator,
}

/** Handy calibration identifiers persisted by earlier versions and used by the motion compiler. */
public enum class HandyProfile(public val storedName: String) {
    Original("handy_original"),
    Standard("handy_2_standard"),
    Pro("handy_2_pro"),
}

/** The personal limits that can be changed from a device page. */
public enum class DeviceLimit {
    VibrationMaximum,
    TempoMinimum,
    TempoMaximum,
}

/** Independent Coyote strength channels. Strength belongs exclusively to the person. */
public enum class CoyoteChannel {
    A,
    B,
}

/** How the phone reaches a device. */
public enum class ConnectionKind {
    Bluetooth,
    HandyCloud,
    AutoblowCloud,
    IntifaceOnPhone,
    IntifaceOnNetwork,
}

/** A device's connection state (system design §3.1). */
public enum class DeviceStatus {
    NeedsSetup,
    NotConnected,
    Connecting,
    Connected,
    Playing,
    Paused,
    Lost,
}

/** Whether a device takes part in the current or next run. Membership is fixed at the start. */
public sealed interface RunMembership {
    public data class InRun(public val isLead: Boolean) : RunMembership

    /** Connected after the run started, so it joins the next one. */
    public data object NextRun : RunMembership

    public data object NotInRun : RunMembership
}

/** What the app commands one device now. */
public sealed interface DeviceOutput {
    /** A stroker. [requestedPace] is what the source asked for when a limit lowered it. */
    public data class Stroke(
        public val pace: Percent,
        public val requestedPace: Percent?,
        public val low: Percent,
        public val high: Percent,
    ) : DeviceOutput

    /** A vibrator. [followsLead] means its level follows the lead device's motion. */
    public data class Level(
        public val level: Percent,
        public val max: Percent,
        public val followsLead: Boolean,
    ) : DeviceOutput

    /** A persistent-speed device such as the VacuGlide. */
    public data class Tempo(public val tempo: Percent) : DeviceOutput

    /** The Coyote's two channel strengths, in its own units. */
    public data class Channels(
        public val strengthA: Int,
        public val strengthB: Int,
        /** A manual step is waiting for its correlated device report. */
        public val isAwaitingReport: Boolean = false,
    ) : DeviceOutput
}

/** Settings that belong to one device, edited on its page. */
@kotlinx.serialization.Serializable
public sealed interface DeviceSettings {
    /** [model] sets travel and speed limits; [isReversed] is for a Handy mounted upside down. */
    @kotlinx.serialization.Serializable
    public data class Handy(public val model: String, public val isReversed: Boolean) :
        DeviceSettings

    @kotlinx.serialization.Serializable
    public data class Vibrator(public val maxLevel: Percent) : DeviceSettings

    @kotlinx.serialization.Serializable
    public data class Coyote(public val maxStrengthA: Int, public val maxStrengthB: Int) :
        DeviceSettings

    /** An Intiface linear axis with a host-side mounting direction. */
    @kotlinx.serialization.Serializable
    public data class LinearActuator(public val isReversed: Boolean = false) : DeviceSettings

    @kotlinx.serialization.Serializable public data object AutoblowUltra : DeviceSettings

    /**
     * @property tempoRange the slowest to fastest the motor may run, in percent.
     * @property mayStepValve chat and Autopilot may step the valve, up to three steps either way
     *   per session.
     * @property checkInEvery how often a check-in is due while it runs; null when off.
     */
    @kotlinx.serialization.Serializable
    public data class VacuGlide(
        @kotlinx.serialization.Serializable(with = RangeSerializer::class)
        public val tempoRange: IntRange,
        public val mayStepValve: Boolean,
        public val checkInEvery: Duration?,
    ) : DeviceSettings
}

/** What a device does by itself if the app stops talking to it (plan §10). */
public enum class AfterLossBehaviour {
    /** The Handy pauses when its motion buffer runs out. */
    PausesWhenBufferRunsOut,

    /** Intiface Central should stop the device when the app disconnects. */
    StopsWhenAppDisconnects,

    /** The Coyote goes silent when packets stop. */
    GoesSilent,

    /** The Ultra or a linear bridge actuator may finish its last submitted move. */
    FinishesLastMove,

    /** The VacuGlide may keep running at its last speed. */
    KeepsLastSpeed,
}

/** A Bluetooth scan for one device family. */
public sealed interface ScanState {
    public data object Idle : ScanState

    /**
     * [results] keep their order while you choose. Discovery ends when one is chosen, and the list
     * stays.
     */
    public data class Scanning(
        public val family: DeviceFamily,
        public val results: List<ScanResult>,
    ) : ScanState

    public data class Failed(public val family: DeviceFamily, public val cause: ScanFailure) :
        ScanState
}

/** One device found by a scan. [signalDbm] is the received signal strength. */
public data class ScanResult(
    public val id: ScanResultId,
    public val name: String,
    public val signalDbm: Int,
    public val status: ScanResultStatus,
)

/** Where a found device is in connecting and verification. */
public enum class ScanResultStatus {
    Found,
    Verifying,
    Verified,
    Failed,
}

/** Why a scan couldn't run or found nothing. */
public enum class ScanFailure {
    BluetoothOff,
    PermissionDenied,
    NothingFound,
}

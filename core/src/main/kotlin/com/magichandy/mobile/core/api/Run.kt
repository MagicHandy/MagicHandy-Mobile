package com.magichandy.mobile.core.api

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * What the app is commanding now, for the Now playing strip and Live.
 *
 * @property commandedPace the lead device's commanded pace; null while nothing plays.
 * @property source who set the current motion; null while nothing plays.
 * @property limits caps that apply to every source: you, chat and Autopilot.
 * @property recentChanges meaningful changes in this run, newest first, bounded.
 * @property isPlanningHeld the model connection dropped during Autopilot. [phase] says whether the
 *   current segment still plays.
 */
public data class RunState(
    public val phase: RunPhase,
    public val commandedPace: Percent?,
    public val source: MotionSource?,
    public val limits: Limits,
    public val recentChanges: List<RunEvent>,
    public val isPlanningHeld: Boolean,
)

/** The run's motion state. Edging is the gentle hold. */
public enum class RunPhase {
    Idle,
    Playing,
    Paused,
    Edging,
}

/** Who set the current motion. */
public enum class MotionSource {
    You,
    Chat,
    Autopilot,
}

/** Limits that every source obeys. Edits while idle never start motion. */
@kotlinx.serialization.Serializable
public data class Limits(
    public val topSpeed: Percent,
    public val strokeLow: Percent,
    public val strokeHigh: Percent,
)

/** One of the three limits on Live. */
public enum class LimitKind {
    TopSpeed,
    StrokeLow,
    StrokeHigh,
}

/** A one-step change on a stepper. The core owns the step size. */
public enum class StepDirection {
    Down,
    Up,
}

/**
 * One meaningful change in the current run, for Recent changes on Live.
 *
 * @property requested what the source asked for, when a limit changed it; null otherwise.
 * @property limitedBy the limit that applied, if any.
 */
public data class RunEvent(
    public val at: Instant,
    public val source: MotionSource,
    public val control: ControlKind,
    public val commanded: Int,
    public val requested: Int?,
    public val limitedBy: LimitKind?,
)

/**
 * Autopilot's state and settings.
 *
 * @property nextChangeIn time until the planner's next change; null while off, paused or held.
 * @property finishTarget the optional finish target; null when off.
 */
public data class AutopilotState(
    public val status: AutopilotStatus,
    public val nextChangeIn: Duration?,
    public val finishTarget: FinishTarget?,
    public val changeFrequency: ChangeFrequency,
    public val checkIns: CheckIns,
)

/** Autopilot's state. Held means planning waits for the model connection. */
public enum class AutopilotStatus {
    Off,
    Active,
    Paused,
    Held,
}

/** How often Autopilot changes motion, from 1 (rarely) to 8 (often). Auto timing picks when. */
@JvmInline
@kotlinx.serialization.Serializable
public value class ChangeFrequency(public val level: Int) {
    init {
        require(level in MIN..MAX) { "Change frequency must be $MIN..$MAX, was $level" }
    }

    public companion object {
        public const val MIN: Int = 1
        public const val MAX: Int = 8
    }
}

/** Messages from the persona while Autopilot runs. TalkAndAdjust may also change motion. */
public enum class CheckIns {
    Off,
    Talk,
    TalkAndAdjust,
}

/** A finish target, not an automatic stop. [remaining] is null until a run starts. */
public data class FinishTarget(public val total: Duration, public val remaining: Duration?)

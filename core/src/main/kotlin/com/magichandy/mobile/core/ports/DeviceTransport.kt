// Ported from internal/{transport/types,motion/engine}.go at 1b80d72e.
package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.AfterLossBehaviour
import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceOutput
import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.motion.MotionFrame
import com.magichandy.mobile.core.motion.hasIncreasingTimes
import kotlin.time.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow

/** Stable device identity and recovery information. Contains no address, credential or payload. */
@kotlinx.serialization.Serializable
public data class DeviceRef(
    public val id: DeviceId,
    public val name: String,
    public val family: DeviceFamily,
    public val connection: ConnectionKind,
) {
    /** Device behaviour after its host disappears. This is never proof of Stop. */
    public val afterLoss: AfterLossBehaviour
        get() =
            when (family) {
                DeviceFamily.LinearActuator -> AfterLossBehaviour.FinishesLastMove
                DeviceFamily.Handy ->
                    if (
                        connection == ConnectionKind.IntifaceOnPhone ||
                            connection == ConnectionKind.IntifaceOnNetwork
                    )
                        AfterLossBehaviour.FinishesLastMove
                    else AfterLossBehaviour.PausesWhenBufferRunsOut
                DeviceFamily.Vibrator -> AfterLossBehaviour.StopsWhenAppDisconnects
                DeviceFamily.Coyote -> AfterLossBehaviour.GoesSilent
                DeviceFamily.AutoblowUltra -> AfterLossBehaviour.FinishesLastMove
                DeviceFamily.VacuGlide -> AfterLossBehaviour.KeepsLastSpeed
            }
}

/** Generation attached to admitted work. A new Stop invalidates all earlier motion generations. */
@JvmInline public value class Epoch(public val value: Long)

/** Drivers consult this fence at enqueue and immediately before each individual mutating write. */
public class OutputFence
internal constructor(
    public val epoch: Epoch,
    /** A fresh stream after Resume, within the same Stop epoch and journaled run. */
    public val streamGeneration: Long = 0,
    public val wholeBridge: Boolean = true,
    private val check: () -> Boolean,
) {
    /** True while this motion lease or this exact outstanding Stop attempt can still write. */
    public fun isCurrent(): Boolean = check()
}

/** One finite, ordered batch on the stream clock. The first batch initializes a fresh stream. */
public data class MotionBatch(
    public val frames: List<MotionFrame>,
    public val pace: Percent,
    public val limits: Limits,
    public val isFirst: Boolean,
    /** Prepare and append this first batch, holding Play until every linked buffer is ready. */
    public val deferPlay: Boolean = false,
    /** Full precision points fitted by the engine for an immediate-mode linear owner. */
    public val linearPoints: List<LinearPoint> = emptyList(),
) {
    init {
        require(frames.isNotEmpty() && frames.hasIncreasingTimes())
        require(
            limits.strokeLow.value < limits.strokeHigh.value && pace.value <= limits.topSpeed.value
        )
    }
}

/**
 * Outcome at the transport boundary.
 * - [Accepted]: command bytes were accepted; a late acceptance after fence expiry triggers recovery
 *   Stop.
 * - [Stale]: known pre-submission denial before any write bytes were sent (expired fence or changed
 *   personal ceiling); does not retire the link, and a still-current timed stream skips and
 *   resamples.
 * - [Uncertain]: delivery is uncertain or partially submitted; retires the link and triggers
 *   recovery Stop.
 */
public enum class WriteResult {
    Accepted,
    Stale,
    Uncertain,
}

/**
 * Connection verification may read device state or establish idle, with cancellation as its fence.
 */
public suspend fun DeviceTransport.confirmIdleForConnection(): StopEvidence {
    val job = currentCoroutineContext()[Job]
    return establishIdle(OutputFence(Epoch(-1)) { job?.isActive != false })
}

/** Stop evidence is attributed to the device or describes why the result is unresolved. */
public sealed interface StopEvidence {
    /** The target device acknowledged the correlated Stop. */
    public data object Acknowledged : StopEvidence

    /** No physical acknowledgement. Bridge and write-only completions belong here. */
    public data class Unconfirmed(public val cause: UnconfirmedCause) : StopEvidence
}

/**
 * Monotonic Play origin estimated from its round trip and the longest of the last eight buffered
 * writes.
 */
public data class BufferedPlaybackTiming(
    public val startedAt: Duration? = null,
    public val recentCommandLatency: Duration = Duration.ZERO,
)

/** One device owner. All output enters here from the single motion engine. */
public interface DeviceTransport {
    /**
     * Establish verified idle for a new owner. Implementations may read state or stop, never start
     * motion.
     */
    public suspend fun establishIdle(fence: OutputFence): StopEvidence = stop(fence)

    /** Capabilities for engine-owned fitting of an immediate linear stream. */
    public val linearSampling: LinearSampling?
        get() = null

    /** Latest measured cloud request round trip, when available. */
    public val measuredLatency: Duration?
        get() = null

    /** Timing of an HSP device's accepted buffer and Play, when the transport measures them. */
    public val bufferedTiming: BufferedPlaybackTiming?
        get() = null

    /** Optional device-reported battery percentage from a verified characteristic. */
    public suspend fun readBattery(): Percent? = null

    /** Optional read-only check of the attached generation or remote device state. */
    public val checkConnection: (suspend () -> Boolean)?
        get() = null

    /** The latest device-reported or actually submitted output, when the owner can expose it. */
    public val feedback: StateFlow<DeviceOutput?>?
        get() = null

    /** Buffered owners capable of a separate aligned Play expose this port. */
    public val linkedStart: LinkedStart?
        get() = null

    /**
     * Optional slider startup operations. The engine finishes all owners' lead-ins before normal
     * playback.
     */
    public val startupPosition: StartupPosition?
        get() = null

    /** Stable identity used to match a saved device and its confirmation. */
    public val device: DeviceRef
    /** Maximum elapsed time allowed for a Stop acknowledgement. */
    public val stopTimeout: Duration

    /**
     * Enqueue a finite batch, rechecking [fence] before each wire write. Never retry uncertain
     * motion.
     */
    public suspend fun write(batch: MotionBatch, fence: OutputFence): WriteResult

    /**
     * Preempt queued work. This operation must suspend independently of an occupied motion queue.
     */
    public suspend fun stop(fence: OutputFence): StopEvidence
}

/** Play an already prepared neutral buffer at an offset from the shared monotonic origin. */
public fun interface LinkedStart {
    public suspend fun play(offsetMillis: Long, fence: OutputFence): WriteResult

    /** Read elapsed time at dispatch after any transport clock synchronization. */
    public suspend fun playAligned(offsetMillis: () -> Long, fence: OutputFence): WriteResult =
        play(offsetMillis(), fence)
}

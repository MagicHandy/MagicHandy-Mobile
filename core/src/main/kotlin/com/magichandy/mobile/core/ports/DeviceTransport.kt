// Ported from internal/{transport/types,motion/engine}.go at 9a4c36cf.
package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.AfterLossBehaviour
import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.motion.MotionFrame
import kotlin.time.Duration

/** Stable device identity and recovery information. Contains no address, credential or payload. */
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
                DeviceFamily.Handy -> AfterLossBehaviour.PausesWhenBufferRunsOut
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
internal constructor(public val epoch: Epoch, private val check: () -> Boolean) {
    /** True while this motion lease or this exact outstanding Stop attempt can still write. */
    public fun isCurrent(): Boolean = check()
}

/** One finite, ordered batch on the stream clock. The first batch initializes a fresh stream. */
public data class MotionBatch(
    public val frames: List<MotionFrame>,
    public val pace: Percent,
    public val limits: Limits,
    public val isFirst: Boolean,
) {
    init {
        require(
            frames.isNotEmpty() &&
                frames.zipWithNext().all { (a, b) -> a.timeMillis < b.timeMillis }
        )
        require(
            limits.strokeLow.value < limits.strokeHigh.value && pace.value <= limits.topSpeed.value
        )
    }
}

/**
 * Outcome at the transport boundary. An uncertain write retires the link and requires recovery
 * Stop.
 */
public enum class WriteResult {
    Accepted,
    Stale,
    Uncertain,
}

/** Stop evidence is attributed to the device or describes why the result is unresolved. */
public sealed interface StopEvidence {
    /** The target device acknowledged the correlated Stop. */
    public data object Acknowledged : StopEvidence

    /** No physical acknowledgement. Bridge and write-only completions belong here. */
    public data class Unconfirmed(public val cause: UnconfirmedCause) : StopEvidence
}

/** One device owner. All output enters here from the single motion engine. */
public interface DeviceTransport {
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

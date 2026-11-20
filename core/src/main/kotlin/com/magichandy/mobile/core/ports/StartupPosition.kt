// Ported from internal/transport/types.go at 1b80d72e.
package com.magichandy.mobile.core.ports

/**
 * One read of the slider and its calibrated stroke window. Absolute values share the device's
 * units.
 */
public data class SliderReading(
    public val positionAbsolute: Double,
    public val speedAbsolute: Double,
    public val minimumPercent: Double,
    public val maximumPercent: Double,
    public val minimumAbsolute: Double,
    public val maximumAbsolute: Double,
)

/**
 * Engine-owned startup sequence for a positioned stroker. All operations share the output lease.
 */
public interface StartupPosition {
    /** Mounting used for this start and its lead-in. */
    public val reversed: Boolean

    /**
     * Confirm a stopped device, reusing an existing correlated idle acknowledgement when available.
     */
    public suspend fun halt(fence: OutputFence): StopEvidence

    /**
     * Null only if the verified protocol has no physical position read. A failed supported read
     * throws.
     */
    public suspend fun read(fence: OutputFence): SliderReading?

    /** Submit the engine's finite lead-in as a separate, starvation-pausing stream. */
    public suspend fun position(batch: MotionBatch, fence: OutputFence): WriteResult
}

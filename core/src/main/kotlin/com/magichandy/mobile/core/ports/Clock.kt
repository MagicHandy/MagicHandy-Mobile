package com.magichandy.mobile.core.ports

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Supplies elapsed time for scheduling and wall time for records and explicit clock
 * synchronization.
 */
public interface Clock {
    /** Time since an arbitrary monotonic origin, including time spent asleep. */
    public fun elapsed(): Duration

    /**
     * Current UTC instant for receipts, records and a device's initial clock-offset measurement.
     */
    public fun now(): Instant
}

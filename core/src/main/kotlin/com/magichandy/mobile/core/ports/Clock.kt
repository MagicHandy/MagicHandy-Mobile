package com.magichandy.mobile.core.ports

import kotlin.time.Duration
import kotlin.time.Instant

/** Supplies elapsed time for deadlines and wall time only for display and journal records. */
public interface Clock {
    /** Time since an arbitrary monotonic origin, including time spent asleep. */
    public fun elapsed(): Duration

    /** Current UTC instant displayed on receipts and persisted records. */
    public fun now(): Instant
}

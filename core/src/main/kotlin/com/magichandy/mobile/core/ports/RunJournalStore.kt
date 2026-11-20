package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.DeviceId
import kotlin.time.Instant

/** Unique across process restarts so delayed acknowledgements can only reconcile their own run. */
@JvmInline public value class RunId(public val value: String)

/** Last intended state recorded at a run boundary. */
public enum class RunIntent {
    Start,
    TestMotor,
    Paused,
}

/** Durable recovery record. Stable device references and intended state carry no secrets. */
public data class RunJournal(
    public val runId: RunId,
    public val devices: List<DeviceRef>,
    public val intended: RunIntent,
    public val autopilot: Boolean,
    public val at: Instant,
    public val version: Int = 1,
) {
    init {
        require(devices.isNotEmpty() && devices.distinctBy { it.id }.size == devices.size)
    }
}

/**
 * Atomic, versioned storage independent of the settings database. I/O failure throws IOException.
 */
public interface RunJournalStore {
    /** Return null only for an absent journal. Corruption must fail closed. */
    public suspend fun read(): RunJournal?

    /** Atomically create only if absent. True means the record is durable before return. */
    public suspend fun armIfEmpty(record: RunJournal): Boolean

    /**
     * Atomically remove devices acknowledged by the device or checked locally by the person, only
     * if [runId] still matches. Clear an empty record.
     */
    public suspend fun reconcile(runId: RunId, confirmed: Set<DeviceId>)
}

/**
 * The Android foreground-service boundary. A start can proceed only after positive confirmation.
 */
public fun interface RunHost {
    /** True only once the service is running and can own this run. */
    public suspend fun ensureRunning(): Boolean
}

/**
 * Cold-start credential lookup and Stop-only transport. Must never probe with movement or start a
 * stream.
 */
public fun interface RecoveryStops {
    /**
     * Resolve the saved cloud target and attempt Stop, or return evidence of the actual failure.
     */
    public suspend fun stopSavedCloud(device: DeviceRef, fence: OutputFence): StopEvidence
}

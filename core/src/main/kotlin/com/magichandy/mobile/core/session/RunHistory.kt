package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.motion.ManualTarget
import com.magichandy.mobile.core.ports.MotionBatch
import kotlin.time.Instant

/**
 * Record meaningful changes once at the output boundary; routine buffer refills produce no rows.
 */
internal fun RunState.recordChanges(
    at: Instant,
    target: ManualTarget,
    batch: MotionBatch,
): List<RunEvent> {
    val changes = mutableListOf<RunEvent>()
    fun add(control: ControlKind, value: Int, requested: Int? = null, limit: LimitKind? = null) {
        val previous = recentChanges.firstOrNull { it.control == control }
        if (previous?.commanded != value || previous.requested != requested)
            changes += RunEvent(at, target.source, control, value, requested, limit)
    }
    val requested = target.requested.takeIf { it != batch.pace.value && !target.holding }
    add(
        ControlKind.Pace,
        batch.pace.value,
        requested,
        LimitKind.TopSpeed.takeIf { requested != null },
    )
    add(ControlKind.StrokeLow, target.physical(target.band.first, batch.limits).value)
    add(ControlKind.StrokeHigh, target.physical(target.band.last, batch.limits).value)
    return if (changes.isEmpty()) recentChanges else (changes + recentChanges).take(64)
}

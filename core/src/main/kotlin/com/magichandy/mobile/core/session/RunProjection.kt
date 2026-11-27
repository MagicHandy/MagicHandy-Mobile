package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.core.api.InterruptedRun
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.RunState
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.motion.EngineState
import com.magichandy.mobile.core.ports.RunJournal

internal fun EngineState.hasTempoLead(preferred: DeviceId?): Boolean {
    val id = preferred?.takeIf { it in members } ?: members.firstOrNull()
    return owners[id]?.device?.family == DeviceFamily.VacuGlide
}

/**
 * Compare the newest accepted target with the last batch actually dispatched to the lead device.
 */
internal fun RunState.withPendingPace(engine: EngineState, tempoLead: Boolean): RunState {
    val next = engine.score?.score?.speed?.coerceAtMost(limits.topSpeed.value)
    val pending =
        if (
            phase != RunPhase.Idle &&
                phase != RunPhase.Paused &&
                engine.members.isNotEmpty() &&
                engine.pausedPosition == null &&
                !tempoLead &&
                !engine.edgeRequested &&
                next != null &&
                next > 0 &&
                next != commandedPace?.value
        )
            Percent(next)
        else null
    return if (pending != pendingPace) copy(pendingPace = pending) else this
}

/** Project physical evidence independently from the motion phase shown by the UI. */
internal fun AppState.withRunEvidence(
    engine: EngineState,
    restored: RunJournal?,
    lead: DeviceId?,
): AppState {
    val updated =
        copy(
            run =
                run.withPendingPace(
                    engine,
                    engine.hasTempoLead(lead),
                ),
            banners =
                when {
                    !engine.resumePositionMissed || !engine.canResume ->
                        banners - Banner.ResumePositionNotReached
                    Banner.ResumePositionNotReached in banners -> banners
                    else -> listOf(Banner.ResumePositionNotReached) + banners
                },
            stopReceipt = engine.receipt,
            interruptedRun = restored?.let { interrupted(it, engine) },
        )
    if (engine.members.isEmpty())
        return updated.copy(
            run =
                run.copy(
                    phase = RunPhase.Idle,
                    commandedPace = null,
                    pendingPace = null,
                    source = null,
                    isPlanningHeld = false,
                ),
            devices = devices.copy(devices = devices.devices.map { it.afterStop(engine) }),
        )
    if (engine.pausedPosition == null) return updated
    return updated.copy(
        run = run.copy(phase = RunPhase.Paused, commandedPace = null, pendingPace = null),
        devices =
            devices.copy(
                devices =
                    devices.devices.map { row ->
                        if (row.id !in engine.members) row
                        else
                            row.copy(
                                status =
                                    if (row.id in engine.confirmed) DeviceStatus.Paused
                                    else row.status,
                                output = null,
                                membership = RunMembership.InRun(row.id == lead),
                            )
                    }
            ),
    )
}

private fun DeviceSummary.afterStop(engine: EngineState): DeviceSummary =
    when {
        id in engine.retired && id in engine.owners ->
            copy(status = DeviceStatus.Lost, membership = RunMembership.NotInRun, output = null)
        status == DeviceStatus.Playing ||
            status == DeviceStatus.Paused ||
            membership is RunMembership.InRun ->
            copy(status = DeviceStatus.Connected, membership = RunMembership.NextRun, output = null)
        else -> this
    }

private fun AppState.interrupted(journal: RunJournal, engine: EngineState): InterruptedRun {
    val known =
        (interruptedRun?.outcomes.orEmpty() + engine.evidence?.outcomes.orEmpty()).associateBy {
            it.device
        }
    val outcomes =
        journal.devices
            .filterNot { it.id in engine.reconciled }
            .mapNotNull { known[it.id] }
            .sortedWith(
                compareBy(
                    { it.outcome == StopOutcome.Confirmed || it.family != DeviceFamily.VacuGlide },
                    { it.outcome },
                )
            )
    return InterruptedRun(journal.at, outcomes, journal.autopilot)
}

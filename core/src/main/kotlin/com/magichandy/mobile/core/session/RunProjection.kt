package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.core.api.InterruptedRun
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.motion.EngineState
import com.magichandy.mobile.core.ports.RunJournal

/** Project physical evidence independently from the motion phase shown by the UI. */
internal fun AppState.withRunEvidence(
    engine: EngineState,
    restored: RunJournal?,
    lead: DeviceId?,
): AppState {
    val updated =
        copy(
            stopReceipt = engine.receipt,
            interruptedRun = restored?.let { interrupted(it, engine) },
        )
    if (engine.members.isEmpty())
        return updated.copy(
            run =
                run.copy(
                    phase = RunPhase.Idle,
                    commandedPace = null,
                    source = null,
                    isPlanningHeld = false,
                ),
            devices = devices.copy(devices = devices.devices.map { it.afterStop(engine) }),
        )
    if (engine.pausedPosition == null) return updated
    return updated.copy(
        run = run.copy(phase = RunPhase.Paused, commandedPace = null),
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

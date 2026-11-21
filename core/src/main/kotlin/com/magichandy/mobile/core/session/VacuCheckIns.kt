package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.api.StopSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Each timer belongs to one playing device. Changing its interval, pausing or Stop cancels it. */
internal class VacuCheckIns(
    private val d: CoreDependencies,
    private val state: MutableStateFlow<AppState>,
    private val core: MagicHandyCore,
) {
    private data class Due(val interval: Duration, val job: Job)

    private val timers = mutableMapOf<DeviceId, Due>()

    private data class Answer(val deadline: Duration, val signal: CompletableDeferred<Unit>)

    private val answers = mutableMapOf<DeviceId, Answer>()

    init {
        d.scope.launch(d.dispatchers.session) {
            state
                .map { s ->
                    s.devices.devices
                        .mapNotNull { row ->
                            val interval = (row.settings as? DeviceSettings.VacuGlide)?.checkInEvery
                            if (
                                interval != null &&
                                    row.status == DeviceStatus.Playing &&
                                    row.membership is RunMembership.InRun
                            )
                                row.id to interval
                            else null
                        }
                        .toMap()
                }
                .distinctUntilChanged()
                .collect { active ->
                    for ((id, timer) in timers.toMap()) if (active[id] != timer.interval) {
                        timer.job.cancel()
                        timers.remove(id)
                        answers.remove(id)
                        dismiss(id)
                    }
                    for ((id, interval) in active) if (id !in timers)
                        timers[id] = Due(interval, launch { monitor(id, interval) })
                }
        }
    }

    fun answer(id: DeviceId) {
        answers[id]?.takeIf { d.clock.elapsed() < it.deadline }?.signal?.complete(Unit)
    }

    private fun dismiss(id: DeviceId) {
        state.update { s ->
            s.copy(banners = s.banners.filterNot { it is Banner.CheckInDue && it.device == id })
        }
    }

    private suspend fun monitor(id: DeviceId, interval: Duration) {
        while (currentCoroutineContext().isActive) {
            delay(interval)
            val name = state.value.devices.devices.find { it.id == id }?.name ?: return
            val ack = CompletableDeferred<Unit>()
            val deadline = d.clock.elapsed() + 1.minutes
            answers[id] = Answer(deadline, ack)
            while (!ack.isCompleted) {
                val remaining = deadline - d.clock.elapsed()
                if (!remaining.isPositive()) break
                state.update { current ->
                    current.copy(
                        banners =
                            listOf(Banner.CheckInDue(id, name, remaining)) +
                                current.banners.filterNot {
                                    it is Banner.CheckInDue && it.device == id
                                }
                    )
                }
                withTimeoutOrNull(minOf(1.seconds, remaining)) { ack.await() }
            }
            val answered = ack.isCompleted
            answers.remove(id)
            dismiss(id)
            if (
                !answered &&
                    state.value.devices.devices.any {
                        it.id == id && it.status == DeviceStatus.Playing
                    }
            ) {
                core.emergencyStop(StopSource.CheckInTimeout)
                return
            }
        }
    }
}

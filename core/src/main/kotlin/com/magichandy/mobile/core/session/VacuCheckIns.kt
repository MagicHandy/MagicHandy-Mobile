package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Each timer belongs to one playing device. Changing its interval, pausing or Stop cancels it. */
internal class VacuCheckIns(
    private val d: CoreDependencies,
    private val state: MutableStateFlow<AppState>,
    private val core: MagicHandyCore,
) {
    private data class Due(val interval: Duration, val job: Job)

    private val timers = mutableMapOf<DeviceId, Due>()
    private val answers = mutableMapOf<DeviceId, CompletableDeferred<Unit>>()

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
        answers[id]?.complete(Unit)
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
            answers[id] = ack
            state.update {
                it.copy(banners = listOf(Banner.CheckInDue(id, name, 1.minutes)) + it.banners)
            }
            val answered =
                withTimeoutOrNull(1.minutes) {
                    ack.await()
                    true
                } == true
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

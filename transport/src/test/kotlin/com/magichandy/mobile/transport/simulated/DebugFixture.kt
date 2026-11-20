package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.CoreDispatchers
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.createMagicHandyCore
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.NetworkMonitor
import com.magichandy.mobile.core.ports.NetworkState
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
internal class DebugFixture(
    val test: TestScope,
    lanes: CoreDispatchers? = null,
    customize: (CoreDependencies) -> CoreDependencies = { it },
) {
    val dispatcher = StandardTestDispatcher(test.testScheduler)
    val scope =
        CoroutineScope(
            test.backgroundScope.coroutineContext +
                SupervisorJob(test.backgroundScope.coroutineContext[Job])
        )
    val clock =
        object : Clock {
            override fun elapsed() = test.testScheduler.currentTime.milliseconds

            override fun now() = Instant.fromEpochMilliseconds(test.testScheduler.currentTime)
        }
    val devices = SimulatedDevices(clock)
    val network = MutableStateFlow(NetworkState(true, 0))
    val d =
        simulatedDependencies(
                scope,
                lanes
                    ?: CoreDispatchers(
                        dispatcher,
                        dispatcher,
                        dispatcher,
                        dispatcher,
                        dispatcher,
                    ),
                clock,
                devices,
            )
            .copy(network = NetworkMonitor { network })
    val core = createMagicHandyCore(customize(d))

    fun send(intent: UserIntent) {
        core.dispatch(intent)
        test.runCurrent()
    }

    fun onboard() = send(UserIntent.AcknowledgeAdultsOnly)

    fun connect(id: DeviceId) = send(UserIntent.Connect(id))
}

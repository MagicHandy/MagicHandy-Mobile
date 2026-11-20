package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.CoreDispatchers
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.ModelCatalog
import com.magichandy.mobile.core.ports.NetworkMonitor
import com.magichandy.mobile.core.ports.NetworkState
import com.magichandy.mobile.core.ports.RunHost
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.testing.InMemoryChats
import com.magichandy.mobile.core.testing.InMemoryDiagnostics
import com.magichandy.mobile.core.testing.InMemoryModels
import com.magichandy.mobile.core.testing.InMemoryRunJournal
import com.magichandy.mobile.core.testing.InMemorySettings
import com.magichandy.mobile.core.testing.InMemoryVault
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Standalone JVM debug wiring before Android ports exist. The process scope owns all three
 * dedicated executors. Cancel it at teardown after completing the app's orderly Stop.
 */
public fun simulatedDependencies(scope: CoroutineScope): CoreDependencies {
    val lifetime =
        requireNotNull(scope.coroutineContext[Job]) { "A process-owned scope is required" }
    fun threads(name: String) = ThreadFactory { task ->
        Thread(task, name).apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
        }
    }
    val output =
        Executors.newSingleThreadExecutor(threads("MagicHandy debug output"))
            .asCoroutineDispatcher()
    val deadlines =
        Executors.newSingleThreadExecutor(threads("MagicHandy debug Stop")).asCoroutineDispatcher()
    val stops =
        Executors.newCachedThreadPool(threads("MagicHandy debug device Stop"))
            .asCoroutineDispatcher()
    lifetime.invokeOnCompletion {
        output.close()
        deadlines.close()
        stops.close()
    }
    val dispatchers =
        CoreDispatchers(
            Dispatchers.Default.limitedParallelism(1),
            output,
            deadlines,
            Dispatchers.IO,
            stops,
        )
    val clock =
        object : Clock {
            private val origin = TimeSource.Monotonic.markNow()

            override fun elapsed(): Duration = origin.elapsedNow()

            override fun now(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        }
    return simulatedDependencies(scope, dispatchers, clock)
}

/**
 * Create isolated debug wiring. All five devices start disconnected, and chat uses a canned model.
 * The caller owns the scope, dispatchers and clock. Release builds must supply Android ports.
 */
public fun simulatedDependencies(
    scope: CoroutineScope,
    dispatchers: CoreDispatchers,
    clock: Clock,
    devices: SimulatedDevices = SimulatedDevices(),
): CoreDependencies {
    val id = ConnectionId("simulated-model")
    val model =
        ModelConnection(
            id,
            "Simulated model",
            ProviderKind.OpenAiCompatible,
            "simulated-text",
            "Simulated",
            CapabilityLevel.Advanced,
            false,
            false,
            ConnectionStatus.Ready,
        )
    val provider = SimulatedModelProvider()
    val network = MutableStateFlow(NetworkState(true, 0))
    return CoreDependencies(
        scope,
        dispatchers,
        clock,
        RunHost { true },
        NetworkMonitor { network },
        devices,
        InMemoryChats(),
        InMemorySettings(),
        InMemoryModels(ModelCatalog(mapOf(id to SavedModel(model)), id)),
        InMemoryRunJournal(),
        InMemoryVault(),
        InMemoryDiagnostics(),
        { saved -> if (saved.info.id == id) provider else null },
    )
}

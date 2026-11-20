@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.RunId
import com.magichandy.mobile.core.ports.RunIntent
import com.magichandy.mobile.core.ports.RunJournal
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

internal fun MotionEngine.admit(expected: Epoch, owners: List<DeviceTransport>): Boolean =
    admit(
        expected,
        owners,
        RunJournal(
            RunId("synthetic"),
            owners.map { it.device },
            RunIntent.Start,
            false,
            Instant.fromEpochMilliseconds(0),
        ),
    )

internal suspend fun MotionEngine.write(
    expected: Epoch,
    owner: DeviceTransport,
    batch: MotionBatch,
): WriteResult = write(stream(expected), owner, batch)

internal val batch =
    MotionBatch(
        listOf(MotionFrame(0, Percent(50))),
        Percent(25),
        Limits(Percent(43), Percent(0), Percent(100)),
        true,
    )

internal class FakeOwner(
    name: String,
    connection: ConnectionKind = ConnectionKind.Bluetooth,
    family: DeviceFamily = DeviceFamily.Handy,
) : DeviceTransport {
    override val device = DeviceRef(DeviceId(name), name, family, connection)
    override var stopTimeout = 400.milliseconds
    var stops = 0
    var writes = 0
    var onStop: suspend (OutputFence) -> StopEvidence = { StopEvidence.Acknowledged }
    var beforeWrite: suspend () -> Unit = {}
    var result = WriteResult.Accepted

    override suspend fun stop(fence: OutputFence): StopEvidence {
        stops++
        return onStop(fence)
    }

    override suspend fun write(batch: MotionBatch, fence: OutputFence): WriteResult {
        beforeWrite()
        if (!fence.isCurrent()) return WriteResult.Stale
        writes++
        return result
    }
}

internal class EngineFixture(val scope: TestScope) {
    val clock =
        object : Clock {
            override fun elapsed(): Duration = scope.testScheduler.currentTime.milliseconds

            override fun now(): Instant =
                Instant.fromEpochMilliseconds(scope.testScheduler.currentTime)
        }
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val events = mutableListOf<com.magichandy.mobile.core.ports.DiagnosticKind>()
    val engine = MotionEngine(scope, dispatcher, dispatcher, clock, events::add)
}

// Ported from internal/motion/engine.go and internal/httpapi/stop_admission.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.StopEvidence
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Dispatches per-device Stop immediately and settles each receipt against its absolute deadline.
 */
internal class StopDelivery(
    private val scope: CoroutineScope,
    private val stopDispatcher: CoroutineDispatcher,
    private val stopIo: CoroutineDispatcher,
    private val clock: Clock,
    private val record: (DiagnosticKind) -> Unit,
    private val current: MutableStateFlow<EngineState>,
) {
    fun send(attempt: StopAttempt) {
        val id = attempt.target.device.id
        val fence =
            OutputFence(attempt.epoch, wholeBridge = attempt.wholeBridge) {
                current.value.pending[id] === attempt
            }
        val timeout = attempt.target.timeout
        val worker =
            scope.launch(stopIo, start = CoroutineStart.LAZY) {
                if (!fence.isCurrent()) return@launch
                val evidence =
                    try {
                        attempt.target.send(fence)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        record(DiagnosticKind.TransportStopFailed)
                        StopEvidence.Unconfirmed(UnconfirmedCause.WriteFailed)
                    }
                // Keep the absolute deadline live until an older submitted write is settled.
                if (evidence == StopEvidence.Acknowledged && id in current.value.inFlight.values) {
                    current.first { it.pending[id] !== attempt || id !in it.inFlight.values }
                }
                finish(attempt, evidence)
            }
        // A non-cooperative write cannot keep its receipt pending or stall the independent Stop
        // lane.
        val timer =
            scope.launch(stopDispatcher) {
                delay(
                    (timeout - (clock.elapsed() - attempt.pressedAt)).coerceAtLeast(Duration.ZERO)
                )
                finish(attempt, StopEvidence.Unconfirmed(UnconfirmedCause.NoReply))
                worker.cancel()
            }
        worker.invokeOnCompletion { cause ->
            if (cause != null)
                finish(attempt, StopEvidence.Unconfirmed(UnconfirmedCause.WriteFailed))
            timer.cancel()
        }
        worker.start()
    }

    private fun finish(attempt: StopAttempt, evidence: StopEvidence) {
        val ref = attempt.target.device
        val elapsed = clock.elapsed() - attempt.pressedAt
        val outcome =
            if (elapsed >= attempt.target.timeout)
                ref.outcome(StopOutcome.Unconfirmed, cause = UnconfirmedCause.NoReply)
            else
                when (evidence) {
                    is StopEvidence.Unconfirmed ->
                        ref.outcome(StopOutcome.Unconfirmed, cause = evidence.cause)
                    StopEvidence.Acknowledged -> ref.outcome(StopOutcome.Confirmed, elapsed)
                }
        current.update { s ->
            val evidence = s.evidence
            if (s.pending[ref.id] !== attempt || evidence == null) return@update s
            if (ref.id in s.reconciled) return@update s.copy(pending = s.pending - ref.id)
            val updated = evidence.withOutcome(outcome)
            val publish = s.publishesEvidence || outcome.outcome == StopOutcome.Unconfirmed
            val failedPause = s.pausedPosition != null && outcome.outcome == StopOutcome.Unconfirmed
            s.copy(
                members = if (failedPause) emptySet() else s.members,
                pausedPosition = if (failedPause) null else s.pausedPosition,
                edgeRequested = !failedPause && s.edgeRequested,
                pending = s.pending - ref.id,
                confirmed =
                    if (outcome.outcome == StopOutcome.Confirmed) s.confirmed + ref.id
                    else s.confirmed,
                evidence = updated,
                publishesEvidence = publish,
                receipt =
                    if (publish) updated
                    else
                        s.receipt?.let { visible ->
                            if (
                                visible.outcomes.any {
                                    it.device == ref.id && it.outcome != StopOutcome.Confirmed
                                }
                            )
                                visible.withOutcome(outcome)
                            else visible
                        },
            )
        }
    }
}

package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.DiagnosticKind
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * A bounded, process-owned form queue. Waiting for a named server leaves normal controls available.
 */
internal class FormSubmissions(
    d: CoreDependencies,
    private val apply: suspend (FormIntent) -> FormResult,
    private val record: (DiagnosticKind) -> Unit,
    private val legacy: (FormResult) -> Unit,
) {
    private class Request(val intent: FormIntent, val result: CompletableDeferred<FormResult>?)

    private val requests =
        Channel<Request>(
            32,
            onUndeliveredElement = { it.result?.complete(FormResult.Rejected(FormReason.Busy)) },
        )

    init {
        d.scope.launch(d.dispatchers.session) {
            try {
                for (r in requests) {
                    if (r.result?.isCancelled == true) continue
                    try {
                        val result =
                            try {
                                apply(r.intent)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: IOException) {
                                record(DiagnosticKind.StoreUnavailable)
                                FormResult.Rejected(FormReason.StorageUnavailable)
                            } catch (_: RuntimeException) {
                                record(DiagnosticKind.CoreTaskFailed)
                                FormResult.Rejected(FormReason.Unreachable)
                            }
                        r.result?.complete(result) ?: legacy(result)
                    } finally {
                        r.result?.complete(FormResult.Rejected(FormReason.Busy))
                    }
                }
            } finally {
                requests.cancel()
            }
        }
    }

    suspend fun submit(intent: FormIntent): FormResult {
        val result = CompletableDeferred<FormResult>()
        if (requests.trySend(Request(intent, result)).isFailure)
            return FormResult.Rejected(FormReason.Busy)
        return try {
            result.await()
        } finally {
            result.cancel()
        }
    }

    fun dispatch(intent: FormIntent) {
        if (requests.trySend(Request(intent, null)).isFailure)
            legacy(FormResult.Rejected(FormReason.Busy))
    }
}

internal fun reject(reason: FormReason) = FormResult.Rejected(reason)

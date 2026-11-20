package com.magichandy.mobile.core.ports

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * Inherited by the core's session, transport and provider jobs. [enqueue] accepts only a redacted
 * category and must return without waiting for storage. Standalone adapters can install this
 * element in their caller and device-reader scopes.
 */
public class DiagnosticReporter(private val enqueue: (DiagnosticKind) -> Unit) :
    AbstractCoroutineContextElement(Key) {
    /** Finds the current core's reporter through structured coroutine ownership. */
    public companion object Key : CoroutineContext.Key<DiagnosticReporter> {
        /** Enqueue an event for the owning diagnostics sink when running in a reporting scope. */
        public suspend fun record(kind: DiagnosticKind) {
            currentCoroutineContext()[Key]?.enqueue?.invoke(kind)
        }
    }
}

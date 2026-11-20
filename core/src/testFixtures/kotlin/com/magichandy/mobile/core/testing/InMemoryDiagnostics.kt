package com.magichandy.mobile.core.testing

import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticsSink
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Bounded redacted diagnostics for debug builds. */
public class InMemoryDiagnostics(private val capacity: Int = 128) : DiagnosticsSink {
    init {
        require(capacity > 0)
    }

    private val current = MutableStateFlow<List<Pair<Instant, DiagnosticKind>>>(emptyList())
    /** Snapshot in insertion order. */
    public val events: StateFlow<List<Pair<Instant, DiagnosticKind>>> = current.asStateFlow()

    override suspend fun record(at: Instant, kind: DiagnosticKind) {
        current.update { (it + (at to kind)).takeLast(capacity) }
    }
}

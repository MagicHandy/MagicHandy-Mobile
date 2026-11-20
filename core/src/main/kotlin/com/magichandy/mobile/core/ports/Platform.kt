package com.magichandy.mobile.core.ports

import kotlin.time.Instant
import kotlinx.coroutines.flow.StateFlow

/** Default network identity changes on handover even if the new network is also validated. */
public data class NetworkState(public val isOnline: Boolean, public val routeGeneration: Long)

/** Android NetworkCallback supplies this state. No probes or background pings are required. */
public fun interface NetworkMonitor {
    /** Current connectivity and subsequent transitions. */
    public fun observe(): StateFlow<NetworkState>
}

/** Redacted lifecycle events. The event cannot carry prompts, keys, addresses or wire payloads. */
public enum class DiagnosticKind {
    StopRequested,
    StopUnconfirmed,
    JournalUnavailable,
    StoreUnavailable,
    TransportWriteFailed,
    TransportStopFailed,
    CoreTaskFailed,
    DeviceReconciled,
    DeviceConnectionFailed,
    ServiceUnavailable,
    BluetoothNotificationsFailed,
    ProviderUnavailable,
    AccountRejected,
    AccountUnavailable,
    WriteFenced,
}

/** A capped rotating event ring in production. Called off the Stop and output paths. */
public fun interface DiagnosticsSink {
    /** Append one event and enforce the implementation's cap. */
    public suspend fun record(at: Instant, kind: DiagnosticKind)
}

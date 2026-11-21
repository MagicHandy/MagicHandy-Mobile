// Ported from internal/transport/intiface_{hub,session,devices}.go at 1b80d72e.
package com.magichandy.mobile.transport.intiface

import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.ports.BridgeSelectionRequired
import com.magichandy.mobile.core.ports.BridgeTarget
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.Epoch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One process-owned v3 connection per canonical address. Discovery and output use reference-counted
 * leases.
 */
public class IntifaceHub(private val http: OkHttpClient, private val scope: CoroutineScope) {
    private class Entry(val session: BridgeSession, val ready: Deferred<Unit>, var users: Int = 0)

    private val entries = mutableMapOf<HttpUrl, Entry>()

    private suspend fun acquire(address: String): Pair<BridgeSession, () -> Unit> {
        val origin = Request.Builder().url(address).build().url
        val entry =
            synchronized(entries) {
                (entries[origin]?.takeUnless { it.ready.isCancelled || it.session.isRetired }
                        ?: run {
                            val session = BridgeSession(scope)
                            Entry(
                                    session,
                                    scope.async(start = CoroutineStart.LAZY) {
                                        session.open(http, address)
                                    },
                                )
                                .also { entries[origin] = it }
                        })
                    .also { it.users++ }
            }
        val released = AtomicBoolean()
        fun release() {
            if (!released.compareAndSet(false, true)) return
            release(origin, entry)
        }
        try {
            entry.ready.await()
        } catch (e: Throwable) {
            release()
            throw e
        }
        return entry.session to ::release
    }

    private fun release(origin: HttpUrl, entry: Entry) {
        synchronized(entries) {
            if (--entry.users == 0) {
                entries.remove(origin, entry)
                entry.ready.cancel()
                entry.session.close()
            }
        }
    }

    /**
     * Reaches the current server even while its device list is pending. The Stop holds a lease
     * until the reply or deadline.
     */
    public fun emergencyStop(epoch: Epoch) {
        val live =
            synchronized(entries) {
                entries.map { (key, entry) ->
                    entry.users++
                    key to entry
                }
            }
        for ((key, entry) in live) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    entry.ready.await()
                    entry.session.stopAll(epoch) { true }
                } catch (_: IOException) {
                    DiagnosticReporter.record(DiagnosticKind.TransportStopFailed)
                } finally {
                    release(key, entry)
                }
            }
        }
    }

    public suspend fun targets(address: String): List<BridgeTarget> {
        val (session, release) = acquire(address)
        return try {
            session.socket.features().map { it.choice }
        } finally {
            release()
        }
    }

    internal suspend fun claim(
        address: String,
        identity: String,
        expected: DeviceFamily,
        onLoss: () -> Unit,
    ): BridgeLease {
        val (session, release) = acquire(address)
        try {
            val revision = session.revision
            val feature =
                session.socket.features().singleOrNull { it.choice.identity == identity }
                    ?: throw BridgeSelectionRequired()
            if (feature.choice.family != expected) throw BridgeSelectionRequired()
            val lease = session.claim(feature, revision, onLoss, release)
            try {
                lease.stopDevice()
            } catch (e: Throwable) {
                lease.release()
                throw e
            }
            return lease
        } catch (e: Throwable) {
            release()
            throw e
        }
    }
}

internal class BridgeSession(private val scope: CoroutineScope) {
    val socket = ButtplugSocket(::failed, ::changed)
    private val claims = mutableMapOf<Int, BridgeLease>()
    @Volatile
    var revision = 0L
        private set

    private var ping: Job? = null
    private var stopping = 0
    @Volatile private var failed = false

    // A lost owner may keep its lease while a new discovery opens a fresh socket for this origin.
    val isRetired: Boolean
        get() = failed || socket.isClosed

    suspend fun open(http: OkHttpClient, address: String) {
        socket.open(http, address)
        val interval = socket.identify()
        if (interval > 0)
            ping = scope.launch {
                try {
                    while (isActive) {
                        delay((interval / 2).coerceAtLeast(1))
                        socket.begin("Ping").await(minOf(1500, interval))
                    }
                } catch (_: IOException) {
                    failed()
                    socket.close()
                }
            }
    }

    fun claim(f: Feature, observed: Long, onLoss: () -> Unit, release: () -> Unit): BridgeLease =
        synchronized(socket.wire) {
            if (failed || stopping > 0 || observed != revision || f.index in claims)
                throw BridgeSelectionRequired()
            BridgeLease(this, f, onLoss, release).also { claims[f.index] = it }
        }

    fun usable(l: BridgeLease) =
        synchronized(socket.wire) { !failed && claims[l.feature.index] === l && stopping == 0 }

    /** Targeted zero is valid during StopAll only while this lease still owns the device index. */
    fun owns(l: BridgeLease) =
        synchronized(socket.wire) { !failed && claims[l.feature.index] === l }

    fun drop(l: BridgeLease) {
        synchronized(socket.wire) { claims.remove(l.feature.index, l) }
    }

    private fun changed(index: Int) {
        val previous =
            synchronized(socket.wire) {
                revision++
                claims.remove(index)?.also { it.invalidate() }
            }
        previous?.lost?.invoke()
    }

    private fun failed() {
        val affected =
            synchronized(socket.wire) {
                failed = true
                claims.values.toList().also {
                    claims.clear()
                    it.forEach(BridgeLease::invalidate)
                }
            }
        ping?.cancel()
        affected.forEach { it.lost() }
    }

    private var stopEpoch: Epoch? = null
    private var stopResult: Deferred<Boolean>? = null

    suspend fun stopAll(epoch: Epoch, allowed: () -> Boolean): Boolean {
        val result =
            synchronized(socket.wire) {
                if (!allowed() || failed) return false
                val last = stopEpoch
                if (last != null && epoch.value >= 0 && epoch.value <= last.value)
                    return@synchronized requireNotNull(stopResult)
                stopping++
                revision++
                claims.values.forEach(BridgeLease::invalidate)
                val ticket =
                    try {
                        socket.begin("StopAllDevices", allowed = allowed)
                    } catch (e: IOException) {
                        stopping--
                        throw e
                    }
                scope
                    .async(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            ticket.await(1500)
                            true
                        } catch (_: IOException) {
                            failed()
                            socket.close()
                            false
                        } finally {
                            synchronized(socket.wire) { stopping-- }
                        }
                    }
                    .also {
                        if (epoch.value >= 0) {
                            stopEpoch = epoch
                            stopResult = it
                        }
                    }
            }
        return result.await()
    }

    fun close() {
        ping?.cancel()
        socket.close()
    }
}

internal class BridgeLease(
    val session: BridgeSession,
    val feature: Feature,
    val lost: () -> Unit,
    private val releaseRef: () -> Unit,
) {
    val socket
        get() = session.socket

    @Volatile
    var generation = 0L
        private set

    var onInvalidate: () -> Unit = {}

    fun invalidate() {
        synchronized(socket.wire) {
            generation++
            onInvalidate()
        }
    }

    fun current(g: Long) = generation == g && session.usable(this)

    suspend fun stopDevice(allowed: () -> Boolean = { true }) {
        socket.request(
            "StopDeviceCmd",
            data = buildJsonObject { put("DeviceIndex", feature.index) },
            allowed = { allowed() && session.usable(this) },
        )
    }

    fun release() {
        invalidate()
        session.drop(this)
        releaseRef()
    }

    suspend fun close() {
        invalidate()
        try {
            stopDevice()
        } catch (_: IOException) {
            DiagnosticReporter.record(DiagnosticKind.TransportStopFailed)
        } finally {
            release()
        }
    }
}

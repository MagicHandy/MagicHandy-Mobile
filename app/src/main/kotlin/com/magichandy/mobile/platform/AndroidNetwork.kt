package com.magichandy.mobile.platform

import android.content.Context
import android.net.*
import android.os.SystemClock
import com.magichandy.mobile.core.ports.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.*

internal object AndroidClock : Clock {
    override fun elapsed() = SystemClock.elapsedRealtime().milliseconds

    override fun now() = Instant.fromEpochMilliseconds(System.currentTimeMillis())
}

internal class AndroidNetwork(context: Context) : NetworkMonitor {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var active = manager.activeNetwork
    private var generation = 0L
    private val state =
        MutableStateFlow(
            NetworkState(online(active), generation, local(manager.getNetworkCapabilities(active)))
        )

    private fun local(c: NetworkCapabilities?) =
        c != null &&
            (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

    private fun online(n: Network?) =
        n != null &&
            manager
                .getNetworkCapabilities(n)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network != active) {
                    active = network
                    generation++
                }
                state.value =
                    NetworkState(
                        online(network),
                        generation,
                        local(manager.getNetworkCapabilities(network)),
                    )
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                if (network == active)
                    state.value =
                        NetworkState(
                            capabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_VALIDATED
                            ),
                            generation,
                            local(capabilities),
                        )
            }

            override fun onLost(network: Network) {
                if (active == network) {
                    active = null
                    state.value = NetworkState(false, ++generation)
                }
            }
        }

    init {
        manager.registerDefaultNetworkCallback(callback)
    }

    override fun observe() = state.asStateFlow()
}

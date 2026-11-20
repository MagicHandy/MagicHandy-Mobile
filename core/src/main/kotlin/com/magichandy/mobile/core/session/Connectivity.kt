package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.motion.MotionEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Device and provider availability follow Android callbacks and explicit transport evidence. */
internal fun observeConnectivity(
    d: CoreDependencies,
    engine: MotionEngine,
    devices: Devices,
    safety: RunSafety,
    ui: MutableStateFlow<AppState>,
    chat: ChatTurns,
    autopilot: Autopilot,
) {
    d.scope.launch(d.dispatchers.session) {
        d.devices.bluetoothOn.collect { on ->
            ui.update { it.copy(devices = it.devices.copy(isBluetoothOn = on)) }
            if (!on)
                devices.lose(
                    engine.state.value.owners.values
                        .filter { it.device.connection == ConnectionKind.Bluetooth }
                        .map { it.device.id }
                )
        }
    }
    d.scope.launch(d.dispatchers.session) {
        d.devices.losses.collect { devices.lose(listOf(it)) }
    }
    d.scope.launch(d.dispatchers.session) {
        d.devices.returns.collect { id -> safety.retryRecovery(id) }
    }
    val network = d.network.observe()
    var previous = network.value
    d.scope.launch(d.dispatchers.session) {
        network.collect { current ->
            if (previous != current) {
                previous = current
                chat.interrupt(InterruptCause.ConnectionLost)
                autopilot.refresh()
                val owners =
                    engine.state.value.owners.values.filter {
                        it.device.connection in
                            setOf(
                                ConnectionKind.HandyCloud,
                                ConnectionKind.AutoblowCloud,
                                ConnectionKind.IntifaceOnNetwork,
                            )
                    }
                devices.lose(owners.map { it.device.id })
                if (current.isOnline) safety.retryRecovery()
            }
            ui.update {
                it.copy(devices = it.devices.copy(isOnline = current.isOnline))
                    .models(d.models.state.value, current)
            }
        }
    }
}

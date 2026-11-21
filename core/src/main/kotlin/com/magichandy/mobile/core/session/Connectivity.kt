package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.InterruptCause
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.ports.DeviceLinkLoss
import com.magichandy.mobile.core.ports.SavedModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
        val losses = d.devices.connectionLosses ?: d.devices.losses.map { DeviceLinkLoss(it) }
        losses.collect { devices.linkLost(it) }
    }
    d.scope.launch(d.dispatchers.session) {
        d.devices.returns.collect { id -> safety.retryRecovery(id) }
    }
    val network = d.network.observe()
    var previous = network.value
    d.scope.launch(d.dispatchers.session) {
        network.collect { current ->
            if (previous != current) {
                val changedRoute = current.routeGeneration != previous.routeGeneration
                val cloudLost = previous.isOnline && (!current.isOnline || changedRoute)
                val lanLost = previous.hasLocalRoute && (!current.hasLocalRoute || changedRoute)
                val catalog = d.models.state.value
                fun interrupted(model: SavedModel?): Boolean {
                    if (model == null || !model.hasRoute(previous)) return false
                    if (!model.hasRoute(current)) return true
                    return changedRoute && !model.isLoopback
                }
                if (interrupted(catalog.models[catalog.chat]))
                    chat.interrupt(InterruptCause.ConnectionLost)
                val planner = catalog.models[catalog.planner ?: catalog.chat]
                if (
                    interrupted(planner) ||
                        planner?.hasRoute(previous) != planner?.hasRoute(current)
                )
                    autopilot.refresh()
                val owners =
                    engine.state.value.owners.values.filter {
                        when (it.device.connection) {
                            ConnectionKind.HandyCloud,
                            ConnectionKind.AutoblowCloud -> cloudLost
                            ConnectionKind.IntifaceOnNetwork -> lanLost
                            ConnectionKind.Bluetooth,
                            ConnectionKind.IntifaceOnPhone -> false
                        }
                    }
                val returned = current.isOnline && (!previous.isOnline || changedRoute)
                previous = current
                devices.lose(owners.map { it.device.id })
                if (returned) safety.retryRecovery()
            }
            ui.update {
                it.copy(devices = it.devices.copy(isOnline = current.isOnline))
                    .models(d.models.state.value, current)
            }
        }
    }
}

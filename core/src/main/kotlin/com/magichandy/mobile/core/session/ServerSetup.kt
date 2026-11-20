package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class ServerSetup(
    private val d: CoreDependencies,
    private val ui: MutableStateFlow<AppState>,
) {
    private var check: Job? = null
    private var generation = 0L

    fun check(
        address: String,
        model: String? = null,
        level: CapabilityLevel? = null,
        save: UserIntent.SaveServer? = null,
    ) {
        val port =
            d.servers ?: return publish(ServerCheck.Failed(address, ServerCheckFailure.Unreachable))
        val token = ++generation
        check?.cancel()
        publish(ServerCheck.Checking(address))
        check =
            d.scope.launch(d.dispatchers.session) {
                val outcome =
                    try {
                        withContext(d.dispatchers.io) { port.check(address, model, level) }
                    } catch (_: java.io.IOException) {
                        ServerCheck.Failed(address, ServerCheckFailure.Unreachable)
                    }
                if (generation != token) return@launch
                publish(outcome)
                if (
                    save != null &&
                        outcome is ServerCheck.Found &&
                        outcome.repliedIn != null &&
                        outcome.selectedModel == save.model
                ) {
                    val id = ConnectionId(java.util.UUID.randomUUID().toString())
                    val info =
                        ModelConnection(
                            id,
                            save.name.trim().take(96).ifBlank { outcome.selectedModel },
                            outcome.provider,
                            outcome.selectedModel,
                            outcome.selectedModel.substringAfterLast('/'),
                            save.capability,
                            false,
                            outcome.isEncrypted,
                            ConnectionStatus.Ready,
                        )
                    d.models.save(
                        SavedModel(
                            info,
                            address = outcome.address,
                            contextTokens = outcome.contextAllowed ?: outcome.contextNeeded,
                        )
                    )
                }
            }
    }

    fun select(model: String) {
        val found = ui.value.models.serverCheck as? ServerCheck.Found ?: return
        if (model in found.models) check(found.address, model)
    }

    fun save(intent: UserIntent.SaveServer) {
        val found = ui.value.models.serverCheck as? ServerCheck.Found ?: return
        if (found.address == intent.address && found.selectedModel == intent.model)
            check(found.address, intent.model, intent.capability, intent)
    }

    private fun publish(s: ServerCheck) {
        ui.update { it.copy(models = it.models.copy(serverCheck = s)) }
    }
}

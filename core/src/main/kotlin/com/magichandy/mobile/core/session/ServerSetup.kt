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
            }
    }

    fun select(model: String) {
        val found = ui.value.models.serverCheck as? ServerCheck.Found ?: return
        if (model in found.models) check(found.address, model)
    }

    suspend fun save(intent: UserIntent.SaveServer): FormResult {
        val port = d.servers ?: return reject(FormReason.Unsupported)
        if (
            intent.model.isBlank() ||
                intent.model.length > 255 ||
                intent.model.any(Char::isISOControl)
        )
            return reject(FormReason.InvalidModel)
        if (intent.name.trim().length > 96 || intent.name.any(Char::isISOControl))
            return reject(FormReason.InvalidName)
        val token = ++generation
        check?.cancel()
        val assessment =
            try {
                withTimeoutOrNull(30000) {
                    withContext(d.dispatchers.io) {
                        port.verify(intent.address, intent.model, intent.capability)
                    }
                }
            } catch (_: java.io.IOException) {
                null
            } ?: return reject(FormReason.Unreachable)
        if (token != generation) return reject(FormReason.Busy)
        val outcome = assessment.check
        publish(outcome)
        if (assessment.refused) return reject(FormReason.ProviderRefused)
        if (outcome is ServerCheck.Failed)
            return reject(
                when (outcome.cause) {
                    ServerCheckFailure.InvalidAddress,
                    ServerCheckFailure.NotPrivateAddress -> FormReason.InvalidAddress
                    ServerCheckFailure.NoModels -> FormReason.InvalidModel
                    ServerCheckFailure.NotAModelServer,
                    ServerCheckFailure.Unreachable -> FormReason.Unreachable
                }
            )
        if (outcome !is ServerCheck.Found || outcome.repliedIn == null)
            return reject(FormReason.Unreachable)
        if (outcome.selectedModel != intent.model) return reject(FormReason.InvalidModel)
        if (
            d.models.state.value.models.values.any {
                it.address?.trimEnd('/') == outcome.address.trimEnd('/') &&
                    it.info.model == intent.model
            }
        )
            return reject(FormReason.Duplicate)
        val id = ConnectionId(java.util.UUID.randomUUID().toString())
        val info =
            ModelConnection(
                id,
                intent.name.trim().take(96).ifBlank { outcome.selectedModel },
                outcome.provider,
                outcome.selectedModel,
                outcome.selectedModel.substringAfterLast('/'),
                intent.capability,
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
        return FormResult.Accepted
    }

    private fun publish(s: ServerCheck) {
        ui.update { it.copy(models = it.models.copy(serverCheck = s)) }
    }
}

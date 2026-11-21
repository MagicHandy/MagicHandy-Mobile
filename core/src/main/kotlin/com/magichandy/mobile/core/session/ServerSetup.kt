package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.ServerCheck
import com.magichandy.mobile.core.api.ServerCheckFailure
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.SavedModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
        val outcome =
            if (assessment.refused && assessment.check is ServerCheck.Failed)
                ServerCheck.Failed(intent.address, ServerCheckFailure.ProviderRefused)
            else assessment.check
        publish(outcome)
        if (assessment.refused) return reject(FormReason.ProviderRefused)
        val failure =
            when (outcome) {
                is ServerCheck.Failed -> outcome.cause
                is ServerCheck.Found -> outcome.testFailure
                else -> null
            }
        if (failure != null)
            return reject(
                when (failure) {
                    ServerCheckFailure.InvalidAddress,
                    ServerCheckFailure.NotPrivateAddress -> FormReason.InvalidAddress
                    ServerCheckFailure.NoModels,
                    ServerCheckFailure.ModelUnavailable -> FormReason.InvalidModel
                    ServerCheckFailure.ProviderRefused -> FormReason.ProviderRefused
                    ServerCheckFailure.IncompleteReply -> FormReason.IncompleteModelReply
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
        val saved =
            SavedModel(
                info,
                address = outcome.address,
                contextTokens = outcome.contextAllowed ?: outcome.contextNeeded,
            )
        if (intent.selectForChat) d.models.save(saved, selectForChat = true)
        else d.models.save(saved)
        return FormResult.Accepted
    }

    private fun publish(s: ServerCheck) {
        ui.update { it.copy(models = it.models.copy(serverCheck = s)) }
    }
}

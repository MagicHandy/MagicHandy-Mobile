package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ReasoningChoice
import com.magichandy.mobile.core.api.ReasoningEffort
import com.magichandy.mobile.core.api.RouterCatalog
import com.magichandy.mobile.core.api.RouterModel
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.KeyCheck
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.SecretHandle
import com.magichandy.mobile.core.ports.UnusableCredential
import com.magichandy.mobile.core.ports.isValidOpenRouterKey
import com.magichandy.mobile.core.ports.matches
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saving and changing OpenRouter connections, and loading OpenRouter's catalog for the pages that
 * choose a model (docs/openrouter-design.md §4). With [CoreDependencies.openRouter], the key and
 * the model are checked before anything is saved, so a connection that can never reply isn't saved.
 */
internal class OpenRouterSetup(
    private val d: CoreDependencies,
    private val ui: MutableStateFlow<AppState>,
    private val record: (DiagnosticKind) -> Unit,
) {
    private var loading: Job? = null

    /** Loads the catalog into the UI state, unless a load is already under way. */
    fun loadCatalog() {
        val access = d.openRouter ?: return publish(RouterCatalog.Failed)
        if (loading?.isActive == true) return
        if (ui.value.models.routerCatalog !is RouterCatalog.Loaded) publish(RouterCatalog.Loading)
        loading =
            d.scope.launch(d.dispatchers.io) {
                val catalog =
                    try {
                        RouterCatalog.Loaded(access.models())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        record(DiagnosticKind.ProviderUnavailable)
                        RouterCatalog.Failed
                    }
                ui.update { state ->
                    // A refresh that fails keeps the catalog already on screen.
                    if (
                        catalog == RouterCatalog.Failed &&
                            state.models.routerCatalog is RouterCatalog.Loaded
                    )
                        state
                    else state.copy(models = state.models.copy(routerCatalog = catalog))
                }
            }
    }

    suspend fun save(intent: UserIntent.SaveOpenRouter): FormResult {
        val model = intent.model.trim()
        if (!isModelId(model)) return reject(FormReason.InvalidModel)
        if (!intent.apiKey.isValidOpenRouterKey()) return reject(FormReason.InvalidKey)
        if (isDuplicate(null, model, intent.apiKey)) return reject(FormReason.Duplicate)
        val listed =
            when (val checked = verify(intent.apiKey, model)) {
                is Verified.Refused -> return reject(checked.reason)
                is Verified.Listed -> checked.model
            }
        val id = ConnectionId(UUID.randomUUID().toString())
        val info =
            connection(
                id,
                model,
                listed,
                intent.capability,
                intent.isReasoningOn,
                intent.reasoningEffort,
            )
        saveWithKey(
            SavedModel(info, contextTokens = contextLimit(listed)),
            intent.apiKey,
            intent.selectForChat,
        )
        return FormResult.Accepted
    }

    suspend fun edit(intent: UserIntent.EditOpenRouter): FormResult {
        val saved =
            d.models.state.value.models[intent.connection]?.takeIf {
                it.info.provider == ProviderKind.OpenRouter
            } ?: return reject(FormReason.NotFound)
        val handle = saved.credential ?: return reject(FormReason.NotFound)
        val model = intent.model.trim()
        if (!isModelId(model)) return reject(FormReason.InvalidModel)
        val replacement = intent.apiKey
        if (replacement != null && !replacement.isValidOpenRouterKey())
            return reject(FormReason.InvalidKey)
        // A saved key that can no longer be read has to be entered again.
        val key = replacement ?: savedKey(handle) ?: return reject(FormReason.InvalidKey)
        if (isDuplicate(saved.info.id, model, key)) return reject(FormReason.Duplicate)
        val listed =
            when (val checked = verify(replacement, model)) {
                is Verified.Refused -> return reject(checked.reason)
                is Verified.Listed -> checked.model
            }
        val info =
            connection(
                saved.info.id,
                model,
                listed,
                intent.capability,
                intent.isReasoningOn,
                intent.reasoningEffort,
            )
        val updated =
            saved.copy(
                info = info,
                contextTokens =
                    if (listed == null && model == saved.info.model) saved.contextTokens
                    else contextLimit(listed),
            )
        if (replacement == null) d.models.save(updated)
        else saveWithKey(updated, replacement, false)
        return FormResult.Accepted
    }

    /** Publish a new credential handle with the model in the same catalog transaction. */
    private suspend fun saveWithKey(model: SavedModel, key: Secret, selectForChat: Boolean) {
        val staged = SecretHandle("router-" + UUID.randomUUID())
        try {
            d.vault.put(staged, key)
            val updated = model.copy(credential = staged)
            if (selectForChat) d.models.save(updated, selectForChat = true)
            else d.models.save(updated)
        } finally {
            withContext(NonCancellable) {
                val committed = d.models.state.value.models[model.info.id]?.credential == staged
                if (committed) model.credential?.let { d.vault.remove(it) }
                else d.vault.remove(staged)
            }
        }
    }

    /** The model as OpenRouter lists it, after checking a new [key]; null when unchecked. */
    private suspend fun verify(key: Secret?, model: String): Verified {
        val access = d.openRouter ?: return Verified.Listed(null)
        if (key != null) {
            val answer =
                try {
                    withContext(d.dispatchers.io) { access.check(key) }
                } catch (_: IOException) {
                    KeyCheck.Unreachable
                }
            when (answer) {
                KeyCheck.Accepted -> Unit
                KeyCheck.Rejected -> {
                    record(DiagnosticKind.AccountRejected)
                    return Verified.Refused(FormReason.InvalidKey)
                }
                KeyCheck.Unreachable -> {
                    record(DiagnosticKind.AccountUnavailable)
                    return Verified.Refused(FormReason.Unreachable)
                }
            }
        }
        val models =
            try {
                withContext(d.dispatchers.io) { access.models() }
            } catch (_: IOException) {
                record(DiagnosticKind.ProviderUnavailable)
                return Verified.Refused(FormReason.Unreachable)
            }
        publish(RouterCatalog.Loaded(models))
        val listed = models.firstOrNull { it.id == model }
        return if (listed == null) Verified.Refused(FormReason.InvalidModel)
        else Verified.Listed(listed)
    }

    private suspend fun savedKey(handle: SecretHandle): Secret? =
        try {
            d.vault.get(handle)
        } catch (_: UnusableCredential) {
            record(DiagnosticKind.StoreUnavailable)
            null
        }

    private suspend fun isDuplicate(except: ConnectionId?, model: String, key: Secret): Boolean =
        d.models.state.value.models.values.any { saved ->
            saved.info.id != except &&
                saved.info.provider == ProviderKind.OpenRouter &&
                saved.info.model == model &&
                saved.credential?.let { d.vault.matches(it, key) } == true
        }

    private fun publish(catalog: RouterCatalog) {
        ui.update { it.copy(models = it.models.copy(routerCatalog = catalog)) }
    }

    private sealed interface Verified {
        /** [model] is null when the app can't check models. */
        data class Listed(val model: RouterModel?) : Verified

        data class Refused(val reason: FormReason) : Verified
    }
}

private fun connection(
    id: ConnectionId,
    model: String,
    listed: RouterModel?,
    capability: CapabilityLevel,
    isOn: Boolean,
    level: ReasoningEffort?,
): ModelConnection {
    // Without the catalog, the choice is kept as asked; a model that doesn't reason gets Off.
    val reasoning =
        if (listed == null) ReasoningChoice(isOn, level.takeIf { isOn })
        else listed.reasoning?.settle(isOn, level) ?: ReasoningChoice(false, null)
    return ModelConnection(
        id,
        "OpenRouter",
        ProviderKind.OpenRouter,
        model,
        listed?.name ?: model.substringAfterLast('/'),
        capability,
        reasoning.isOn,
        true,
        ConnectionStatus.Ready,
        reasoning.level,
    )
}

/** The mobile default is 64K. Smaller advertised windows constrain both chat and planning. */
private fun contextLimit(listed: RouterModel?): Int =
    minOf(65536, listed?.contextTokens?.takeIf { it > 0 } ?: 65536)

private fun isModelId(model: String): Boolean =
    model.isNotEmpty() &&
        model.length <= 200 &&
        model.none { it.isWhitespace() || it.isISOControl() }

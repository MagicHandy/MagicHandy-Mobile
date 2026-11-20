// Ported from internal/httpapi/{chat,motion,stop_admission}.go at 1b80d72e.
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
import com.magichandy.mobile.core.api.SignInFailure
import com.magichandy.mobile.core.api.SignInState
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.SavedModel
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class ModelAccounts(
    private val d: CoreDependencies,
    private val ui: MutableStateFlow<AppState>,
    private val record: (DiagnosticKind) -> Unit,
    private val unavailable: () -> Unit,
    private val storeFailed: () -> Unit,
) {
    private var signIn: Job? = null

    fun startSignIn() {
        val action = d.signIn ?: return unavailable()
        signIn?.cancel()
        setSignIn(SignInState.WaitingForBrowser)
        signIn =
            d.scope.launch(d.dispatchers.session) {
                val failure =
                    try {
                        action.signIn()
                    } catch (_: IOException) {
                        record(DiagnosticKind.AccountUnavailable)
                        SignInFailure.NetworkError
                    }
                if (!isActive) return@launch
                if (failure != null) {
                    setSignIn(SignInState.Failed(failure))
                    return@launch
                }
                val info =
                    ModelConnection(
                        ConnectionId("chatgpt"),
                        "ChatGPT",
                        ProviderKind.ChatGpt,
                        "gpt-6.1-terra",
                        "Terra",
                        CapabilityLevel.Advanced,
                        true,
                        true,
                        ConnectionStatus.Ready,
                    )
                try {
                    d.models.save(SavedModel(info, d.signInCredential))
                    setSignIn(SignInState.Idle)
                } catch (_: IOException) {
                    storeFailed()
                    setSignIn(SignInState.Failed(SignInFailure.NetworkError))
                }
            }
    }

    suspend fun saveOpenRouter(intent: UserIntent.SaveOpenRouter): FormResult {
        val name = intent.model.trim()
        if (
            name.isEmpty() ||
                name.length > 200 ||
                name.any { it.isWhitespace() || it.isISOControl() }
        )
            return reject(FormReason.InvalidModel)
        val key = intent.apiKey.reveal()
        if (key.length !in 1..2048 || key.any { it.code !in 33..126 })
            return reject(FormReason.InvalidKey)
        for (saved in d.models.state.value.models.values) {
            if (
                saved.info.provider == ProviderKind.OpenRouter &&
                    saved.info.model == name &&
                    saved.credential?.let { d.vault.get(it) } == intent.apiKey
            )
                return reject(FormReason.Duplicate)
        }
        val id = ConnectionId(java.util.UUID.randomUUID().toString())
        val handle = com.magichandy.mobile.core.ports.SecretHandle("router-" + id.value)
        val info =
            ModelConnection(
                id,
                "OpenRouter",
                ProviderKind.OpenRouter,
                name,
                name.substringAfterLast('/'),
                intent.capability,
                intent.isReasoningOn,
                true,
                ConnectionStatus.Ready,
            )
        d.vault.put(handle, intent.apiKey)
        try {
            d.models.save(SavedModel(info, handle))
        } finally {
            if (id !in d.models.state.value.models) d.vault.remove(handle)
        }
        return FormResult.Accepted
    }

    private fun setSignIn(value: SignInState) {
        ui.update { it.copy(models = it.models.copy(signIn = value)) }
    }

    fun cancelSignIn() {
        signIn?.cancel()
        setSignIn(SignInState.Failed(SignInFailure.Cancelled))
    }

    fun checkRelease() {
        if (ui.value.app.update == com.magichandy.mobile.core.api.UpdateCheck.Checking) return
        ui.update {
            it.copy(app = it.app.copy(update = com.magichandy.mobile.core.api.UpdateCheck.Checking))
        }
        d.scope.launch(d.dispatchers.io) {
            val result =
                try {
                    d.releases?.check(d.versionName)
                        ?: com.magichandy.mobile.core.api.UpdateCheck.Failed
                } catch (_: IOException) {
                    com.magichandy.mobile.core.api.UpdateCheck.Failed
                }
            ui.update { it.copy(app = it.app.copy(update = result)) }
        }
    }
}

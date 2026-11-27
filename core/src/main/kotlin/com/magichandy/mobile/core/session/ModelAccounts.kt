// Ported from internal/httpapi/{chat,motion,stop_admission}.go at 1b80d72e.
package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.SignInFailure
import com.magichandy.mobile.core.api.SignInStage
import com.magichandy.mobile.core.api.SignInState
import com.magichandy.mobile.core.api.UpdateCheck
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.SavedModel
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
    private val signedIn: (ConnectionId) -> Unit,
) {
    private var signIn: Job? = null

    /** OpenRouter connections and the catalog their pages choose from. */
    val openRouter = OpenRouterSetup(d, ui, record)

    fun startSignIn(selectForChat: Boolean = false) {
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
                        SignInState.Failed(SignInFailure.NetworkError)
                    }
                if (!isActive) return@launch
                if (failure != null) {
                    setSignIn(failure)
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
                    d.models.save(SavedModel(info, d.signInCredential), selectForChat)
                    signedIn(info.id)
                    ui.update { state ->
                        state.copy(
                            models =
                                state.models.copy(
                                    signIn = SignInState.Idle,
                                    completedSignIns = state.models.completedSignIns + 1,
                                )
                        )
                    }
                } catch (_: IOException) {
                    storeFailed()
                    setSignIn(
                        SignInState.Failed(
                            SignInFailure.StorageUnavailable,
                            SignInStage.SavingConnection,
                        )
                    )
                }
            }
    }

    private fun setSignIn(value: SignInState) {
        ui.update { it.copy(models = it.models.copy(signIn = value)) }
    }

    fun cancelPendingSignIn() {
        if (signIn?.isActive == true) cancelSignIn()
    }

    fun cancelSignIn() {
        signIn?.cancel()
        setSignIn(SignInState.Failed(SignInFailure.Cancelled))
    }

    fun checkRelease() {
        if (ui.value.app.update == UpdateCheck.Checking) return
        ui.update {
            it.copy(app = it.app.copy(update = UpdateCheck.Checking))
        }
        d.scope.launch(d.dispatchers.io) {
            val result =
                try {
                    d.releases?.check(d.versionName) ?: UpdateCheck.Failed
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    record(DiagnosticKind.ProviderUnavailable)
                    UpdateCheck.Failed
                }
            ui.update { it.copy(app = it.app.copy(update = result)) }
        }
    }
}

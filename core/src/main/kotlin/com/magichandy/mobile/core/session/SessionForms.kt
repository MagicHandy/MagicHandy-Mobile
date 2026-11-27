package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.AutopilotStatus
import com.magichandy.mobile.core.api.BackendNotice
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.FormIntent
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.InterruptCause
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.PersonaChange
import com.magichandy.mobile.core.ports.PreferenceField
import com.magichandy.mobile.core.ports.SecretHandle
import com.magichandy.mobile.core.ports.SecretsVault
import com.magichandy.mobile.core.ports.SettingsDocument
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal class SessionForms(
    private val d: CoreDependencies,
    private val ui: MutableStateFlow<AppState>,
    private val engine: MotionEngine,
    private val setup: DeviceSetup,
    private val servers: ServerSetup,
    private val accounts: ModelAccounts,
    private val chat: ChatTurns,
    record: (DiagnosticKind) -> Unit,
) {
    val submissions =
        FormSubmissions(d, ::applyForm, record) { outcome ->
            if (outcome is FormResult.Rejected)
                ui.update {
                    it.copy(
                        backendNotice =
                            when (outcome.reason) {
                                FormReason.ReconcileDevice -> BackendNotice.ReconcileDevices
                                FormReason.RunInProgress -> BackendNotice.RunAlreadyActive
                                FormReason.StorageUnavailable -> BackendNotice.StorageUnavailable
                                else -> BackendNotice.FeatureUnavailable
                            }
                    )
                }
        }

    private suspend fun applyForm(intent: FormIntent): FormResult =
        when (intent) {
            is UserIntent.AddCloudHandy ->
                setup.cloud(
                    DeviceFamily.Handy,
                    intent.name,
                    intent.connectionKey,
                    intent.connectAfterSave,
                )
            is UserIntent.AddAutoblowDevice ->
                setup.cloud(intent.family, intent.name, intent.deviceToken, intent.connectAfterSave)
            is UserIntent.AddIntiface ->
                setup.bridges.intiface(
                    intent.address,
                    intent.deviceIndex,
                    selection = intent.selection,
                    replaceDevice = intent.replaceDevice,
                )
            is UserIntent.AddIntifaceLinear ->
                setup.bridges.intiface(
                    intent.address,
                    intent.deviceIndex,
                    intent.actuatorIndex,
                    true,
                    intent.selection,
                    intent.replaceDevice,
                )
            is UserIntent.Rename -> setup.rename(intent)
            is UserIntent.Remove -> setup.remove(intent.device)
            is UserIntent.SaveOpenRouter -> accounts.openRouter.save(intent)
            is UserIntent.EditOpenRouter -> accounts.openRouter.edit(intent)
            is UserIntent.SaveServer -> servers.save(intent)
            is UserIntent.RemoveConnection -> removeConnection(intent.connection)
            is UserIntent.SavePersona -> editPersonas { it.savePersona(intent) }
            is UserIntent.DuplicatePersona -> editPersonas { it.duplicatePersona(intent) }
            is UserIntent.DeletePersona -> editPersonas { it.deletePersona(intent.persona) }
        }

    private suspend fun editPersonas(edit: (SettingsDocument) -> PersonaChange): FormResult {
        val result = d.settings.editPersonas(edit)
        if (result == FormResult.Accepted)
            ui.update { it.showPersonas(d.settings.state.value, d.chats.state.value) }
        return result
    }

    private suspend fun removeConnection(id: ConnectionId): FormResult {
        val catalog = d.models.state.value
        if (id !in catalog.models) return reject(FormReason.NotFound)
        if (
            (engine.state.value.members.isNotEmpty() ||
                ui.value.autopilot.status != AutopilotStatus.Off) &&
                id in listOf(catalog.planner, catalog.chat)
        )
            return reject(FormReason.RunInProgress)
        if (catalog.models[id]?.info?.provider == ProviderKind.ChatGpt)
            accounts.cancelPendingSignIn()
        if (id == catalog.chat) chat.interrupt(InterruptCause.ProviderError)
        val handle = d.models.state.value.models[id]?.credential
        val settings = d.settings.state.value
        if (
            settings.values.refusalFallback == id &&
                !d.settings.patch(
                    settings.revision,
                    listOf(PreferenceField.RefusalFallback.edit(null)),
                )
        )
            return reject(FormReason.Busy)
        removeWithCredential(d.vault, handle, { id in d.models.state.value.models }) {
            d.models.remove(id)
        }
        return FormResult.Accepted
    }
}

/**
 * Revoke the credential before committing deletion. A failed row transaction leaves the form
 * retryable.
 */
internal suspend fun removeWithCredential(
    vault: SecretsVault,
    handle: SecretHandle?,
    exists: () -> Boolean,
    remove: suspend () -> Unit,
) {
    withContext(NonCancellable) {
        val erased = handle?.let { vault.takeForRemoval(it) }
        try {
            remove()
        } finally {
            if (erased != null && exists()) erased.restoreIfMissing()
        }
    }
}

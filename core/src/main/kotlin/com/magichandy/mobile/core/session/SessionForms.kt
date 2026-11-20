package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.ports.*
import kotlinx.coroutines.flow.*

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
                setup.cloud(DeviceFamily.Handy, intent.name, intent.connectionKey)
            is UserIntent.AddAutoblowDevice ->
                setup.cloud(intent.family, intent.name, intent.deviceToken)
            is UserIntent.AddIntiface ->
                setup.bridges.intiface(
                    intent.address,
                    intent.deviceIndex,
                    selection = intent.selection,
                )
            is UserIntent.AddIntifaceLinear ->
                setup.bridges.intiface(
                    intent.address,
                    intent.deviceIndex,
                    intent.actuatorIndex,
                    true,
                    intent.selection,
                )
            is UserIntent.Rename -> setup.rename(intent)
            is UserIntent.Remove -> setup.remove(intent.device)
            is UserIntent.SaveOpenRouter -> accounts.saveOpenRouter(intent)
            is UserIntent.SaveServer -> servers.save(intent)
            is UserIntent.RemoveConnection -> removeConnection(intent.connection)
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
    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        val erased = handle?.let { vault.takeForRemoval(it) }
        try {
            remove()
        } finally {
            if (erased != null && exists()) erased.restoreIfMissing()
        }
    }
}

package com.magichandy.mobile.core

import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.ports.ChatStore
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DiagnosticsSink
import com.magichandy.mobile.core.ports.ModelProvider
import com.magichandy.mobile.core.ports.ModelSignIn
import com.magichandy.mobile.core.ports.ModelStore
import com.magichandy.mobile.core.ports.NetworkMonitor
import com.magichandy.mobile.core.ports.RunHost
import com.magichandy.mobile.core.ports.RunJournalStore
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.SecretHandle
import com.magichandy.mobile.core.ports.SecretsVault
import com.magichandy.mobile.core.ports.SettingsStore
import com.magichandy.mobile.core.ports.SimulatedSpeaker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Injected execution lanes. [session] and [output] serialize their owners. [stop] is a dedicated
 * elevated-priority deadline thread. [stopIo] runs Stop calls to different owners concurrently and
 * must remain independent of [io], where model, device playback and storage calls run.
 */
public data class CoreDispatchers(
    /** Serializes ordinary intents and publishes session state. */
    public val session: CoroutineDispatcher,
    /** Serializes motion sampling and scheduling. */
    public val output: CoroutineDispatcher,
    /** Runs independent Stop and motor-test deadlines. */
    public val stop: CoroutineDispatcher,
    /** Runs ordinary device, provider and storage I/O. */
    public val io: CoroutineDispatcher,
    /** Runs priority Stop calls and notification readers independently of ordinary I/O. */
    public val stopIo: CoroutineDispatcher,
)

/** Process-owned ports. Cancel [scope] on process teardown. The app wires exactly one core. */
public data class CoreDependencies(
    /** Owns the core for the lifetime of the application process. */
    public val scope: CoroutineScope,
    /** Execution lanes shared by this core and its adapters. */
    public val dispatchers: CoreDispatchers,
    /** Supplies monotonic deadlines and wall timestamps. */
    public val clock: Clock,
    /** Confirms the foreground service is ready before device work. */
    public val runHost: RunHost,
    /** Reports connectivity and default-route changes. */
    public val network: NetworkMonitor,
    /** Resolves saved devices, opens owners and sends detached cloud Stops. */
    public val devices: DeviceDirectory,
    /** Stores transcripts and persona memory in transactions. */
    public val chats: ChatStore,
    /** Applies revisioned preference patches. */
    public val settings: SettingsStore,
    /** Stores model connections and their role bindings. */
    public val models: ModelStore,
    /** Durably arms runs and reconciles individual devices. */
    public val journal: RunJournalStore,
    /** Stores credentials behind opaque handles. */
    public val vault: SecretsVault,
    /** Receives bounded, redacted events off the output and Stop lanes. */
    public val diagnostics: DiagnosticsSink,
    /** Resolves a saved model to its streaming provider, when available. */
    public val provider: (SavedModel) -> ModelProvider?,
    /** Optional account sign-in flow for the registered model provider. */
    public val signIn: ModelSignIn? = null,
    /** Vault handle associated with a successful sign-in. */
    public val signInCredential: SecretHandle? = null,
    /** Display version supplied by the app composition root. */
    public val versionName: String = "development",
    /** Resolves a local model server only after the person supplies its address. */
    public val servers: com.magichandy.mobile.core.ports.ModelServers? = null,
    /** User-triggered public release metadata check. */
    public val releases: com.magichandy.mobile.core.ports.ReleaseFeed? = null,
    /** Android API level supplied by the application, omitted on a plain JVM. */
    public val androidApi: Int? = null,
    /** Await initial durable reads on platforms that load their stores asynchronously. */
    public val awaitStorage: (suspend () -> Unit)? = null,
    /** OpenRouter's catalog and key check. Without it, OpenRouter keys and models are unchecked. */
    public val openRouter: com.magichandy.mobile.core.ports.OpenRouterAccess? = null,
)

/** A captured name and register stay local to the debug model, including at Utility. */
internal fun CoreDependencies.speakingProvider(
    model: SavedModel,
    persona: PersonaRef,
): ModelProvider? =
    when (val peer = provider(model)) {
        is SimulatedSpeaker -> peer.speakingAs(persona.name, persona.personality.explicitness)
        else -> peer
    }

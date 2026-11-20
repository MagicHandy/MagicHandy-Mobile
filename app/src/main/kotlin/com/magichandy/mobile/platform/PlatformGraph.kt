package com.magichandy.mobile.platform

import android.os.Process
import com.magichandy.mobile.MagicHandyApp
import com.magichandy.mobile.core.*
import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.platform.storage.*
import com.magichandy.mobile.transport.providers.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient

internal fun platformCore(app: MagicHandyApp): MagicHandyCore {
    val scope = app.processScope
    val stores = RoomStores(PlatformDatabase.open(app), scope)
    val root = app.noBackupFilesDir
    val vault = AndroidVault(File(root, "credentials"))
    val http =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    val network = AndroidNetwork(app)
    val devices = PlatformDevices(app, stores, vault, http, scope, network)
    app.refreshRadio = devices::refresh
    val handle = SecretHandle("chatgpt-account")
    val account by lazy {
        val idFile = AtomicBytes(File(root, "agent-host"))
        val hostId =
            idFile.read(128)?.decodeToString()
                ?: UUID.randomUUID().toString().also { idFile.replace(it.encodeToByteArray()) }
        ChatGptAccount(http, LoopbackBrowser(app, scope), vault, handle, AndroidClock, hostId)
    }
    val signIn = ModelSignIn { withContext(Dispatchers.IO) { account.signIn() } }
    val stop =
        Executors.newSingleThreadExecutor { task ->
                Thread(
                    {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                        task.run()
                    },
                    "Motion Stop",
                )
            }
            .asCoroutineDispatcher()
    val stopIo =
        Executors.newCachedThreadPool { r -> Thread(r, "Device Stop") }.asCoroutineDispatcher()
    scope.coroutineContext[Job]?.invokeOnCompletion {
        stop.close()
        stopIo.close()
    }
    val providers = java.util.concurrent.ConcurrentHashMap<SavedModel, ModelProvider>()
    val servers = LanServers(http)
    scope.launch {
        stores.models.state.collect { catalog ->
            providers.keys.retainAll(catalog.models.values.toSet())
        }
    }
    fun provider(m: SavedModel): ModelProvider? {
        providers[m]?.let {
            return it
        }
        val resolved =
            when (m.info.provider) {
                ProviderKind.ChatGpt ->
                    ResponsesProvider(
                        http,
                        AccessTokenSource { account.token() },
                        m.info.model,
                        m.contextTokens,
                    )
                ProviderKind.OpenRouter ->
                    CompatibleProvider.openRouter(
                        http,
                        AccessTokenSource { m.credential?.let { vault.get(it) } },
                        m.info.model,
                        m.info.isReasoningOn,
                        m.contextTokens.takeIf { it > 0 } ?: 65536,
                    )
                ProviderKind.Ollama,
                ProviderKind.LlamaCpp,
                ProviderKind.OpenAiCompatible -> servers.provider(m) ?: return null
            }
        return providers.putIfAbsent(m, resolved) ?: resolved
    }
    return createMagicHandyCore(
        CoreDependencies(
            scope,
            CoreDispatchers(
                Dispatchers.Default.limitedParallelism(1),
                Dispatchers.Default.limitedParallelism(1),
                stop,
                Dispatchers.IO,
                stopIo,
            ),
            AndroidClock,
            app.host,
            network,
            devices,
            stores.chats,
            stores.settings,
            stores.models,
            FileJournal(root),
            vault,
            RotatingDiagnostics(File(root, "diagnostics")),
            ::provider,
            signIn,
            handle,
            app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty(),
            servers,
            com.magichandy.mobile.transport.http.MobileReleases(http),
        )
    )
}

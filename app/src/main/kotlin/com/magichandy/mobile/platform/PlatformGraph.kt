package com.magichandy.mobile.platform

import android.os.Process
import com.magichandy.mobile.MagicHandyApp
import com.magichandy.mobile.R
import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.CoreDispatchers
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.SignInFailure
import com.magichandy.mobile.core.api.SignInState
import com.magichandy.mobile.core.createMagicHandyCore
import com.magichandy.mobile.core.ports.ModelProvider
import com.magichandy.mobile.core.ports.ModelSignIn
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.SecretHandle
import com.magichandy.mobile.platform.storage.AndroidVault
import com.magichandy.mobile.platform.storage.AtomicBytes
import com.magichandy.mobile.platform.storage.FileJournal
import com.magichandy.mobile.platform.storage.PlatformDatabase
import com.magichandy.mobile.platform.storage.RoomStores
import com.magichandy.mobile.platform.storage.RotatingDiagnostics
import com.magichandy.mobile.platform.storage.chatGptHostId
import com.magichandy.mobile.transport.http.HttpsOnly
import com.magichandy.mobile.transport.http.MobileReleases
import com.magichandy.mobile.transport.providers.AccessTokenSource
import com.magichandy.mobile.transport.providers.ChatGptAccount
import com.magichandy.mobile.transport.providers.CompatibleProvider
import com.magichandy.mobile.transport.providers.LanServers
import com.magichandy.mobile.transport.providers.ResponsesProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal fun platformCore(app: MagicHandyApp): MagicHandyCore {
    val scope = app.processScope
    val stores = RoomStores(PlatformDatabase.open(app), scope)
    val root = app.noBackupFilesDir
    val vault = AndroidVault(File(root, "credentials"))
    val http =
        OkHttpClient.Builder()
            .addInterceptor(HttpsOnly)
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
        val hostId = chatGptHostId(AtomicBytes(File(root, "agent-host")))
        ChatGptAccount(
            http,
            LoopbackBrowser(
                scope,
                app.signInTabs::awaitForeground,
                { app.getString(R.string.chatgpt_browser_return) },
                app.signInTabs::open,
            ),
            vault,
            handle,
            AndroidClock,
            hostId,
            stores::requireChatGptSignIn,
        )
    }
    val signIn = ModelSignIn {
        withContext(Dispatchers.IO) {
            try {
                account.signIn()
            } catch (_: IOException) {
                SignInState.Failed(SignInFailure.StorageUnavailable)
            }
        }
    }
    fun motionDispatcher(name: String) =
        Executors.newSingleThreadExecutor { task ->
                Thread(
                    {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                        task.run()
                    },
                    name,
                )
            }
            .asCoroutineDispatcher()
    val stop = motionDispatcher("Motion Stop")
    val output = motionDispatcher("Motion output")
    val stopIo =
        Executors.newCachedThreadPool { r -> Thread(r, "Device Stop") }.asCoroutineDispatcher()
    scope.coroutineContext[Job]?.invokeOnCompletion {
        stop.close()
        output.close()
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
                        object : AccessTokenSource {
                            override suspend fun token() = account.token()

                            override suspend fun rejected(token: Secret) = account.rejected(token)
                        },
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
                output,
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
            MobileReleases(http),
            androidApi = android.os.Build.VERSION.SDK_INT,
            awaitStorage = stores::awaitReady,
        )
    )
}

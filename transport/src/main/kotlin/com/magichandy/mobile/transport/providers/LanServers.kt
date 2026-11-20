package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.transport.http.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource
import kotlinx.serialization.json.*
import okhttp3.*

public class LanServers(private val client: OkHttpClient) : ModelServers {
    override suspend fun check(
        address: String,
        model: String?,
        level: CapabilityLevel?,
    ): ServerCheck {
        val base =
            serverAddress(address)
                ?: return ServerCheck.Failed(address, ServerCheckFailure.InvalidAddress)
        val http = originClient(client, base).newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
        return try {
            val peer =
                detect(http, base)
                    ?: return ServerCheck.Failed(address, ServerCheckFailure.NotAModelServer)
            val selected =
                model?.takeIf { it in peer.models }
                    ?: peer.models.firstOrNull()
                    ?: return ServerCheck.Failed(address, ServerCheckFailure.NoModels)
            val known =
                when {
                    selected.contains("gemma", true) && selected.contains("12b", true) ->
                        CapabilityLevel.Intermediate
                    selected.contains("gemma", true) && selected.contains("e4b", true) ->
                        CapabilityLevel.Simple
                    else -> null
                }
            val suggested = known ?: CapabilityLevel.Simple
            val needed =
                when (level ?: suggested) {
                    CapabilityLevel.Simple -> 16384
                    CapabilityLevel.Intermediate -> 32768
                    CapabilityLevel.Advanced -> 65536
                }
            val start = TimeSource.Monotonic.markNow()
            val provider = localProvider(http, base, peer.kind, selected, needed)
            val reply = provider.stream(listOf(TextMessage(TextRole.User, "Reply with OK."))) {}
            if (reply !is TextResult.Completed)
                return ServerCheck.Failed(address, ServerCheckFailure.Unreachable)
            ServerCheck.Found(
                address.trim(),
                peer.kind,
                base.isHttps,
                peer.models,
                selected,
                suggested,
                known,
                needed,
                peer.context,
                start.elapsedNow(),
            )
        } catch (_: PrivateAddressRequired) {
            ServerCheck.Failed(address, ServerCheckFailure.NotPrivateAddress)
        } catch (failure: IOException) {
            val denied =
                generateSequence<Throwable>(failure) { it.cause }
                    .take(8)
                    .any { it is PrivateAddressRequired }
            ServerCheck.Failed(
                address,
                if (denied) ServerCheckFailure.NotPrivateAddress
                else ServerCheckFailure.Unreachable,
            )
        }
    }

    /** Build only from a saved, explicitly checked origin. */
    public fun provider(model: SavedModel): StructuredProvider? {
        val base = serverAddress(model.address ?: return null) ?: return null
        return localProvider(
            originClient(client, base),
            base,
            model.info.provider,
            model.info.model,
            model.contextTokens.takeIf { it > 0 } ?: 32768,
        )
    }

    private fun localProvider(
        http: OkHttpClient,
        base: HttpUrl,
        kind: ProviderKind,
        model: String,
        context: Int,
    ): StructuredProvider =
        if (kind == ProviderKind.Ollama) OllamaProvider(http, base, model, context)
        else
            CompatibleProvider(
                http,
                requireNotNull(base.resolve("v1/")),
                model,
                null,
                false,
                context,
            )

    private data class Peer(val kind: ProviderKind, val models: List<String>, val context: Int?)

    private suspend fun detect(http: OkHttpClient, base: HttpUrl): Peer? {
        suspend fun get(path: String): JsonObject? =
            http.exchange(Request.Builder().url(requireNotNull(base.resolve(path))).build()) { res
                ->
                if (!res.isSuccessful) null
                else
                    try {
                        Json.parseToJsonElement(res.boundedText(1024L * 1024)) as? JsonObject
                    } catch (_: IllegalArgumentException) {
                        null
                    }
            }
        fun names(rows: JsonArray, key: String) =
            rows
                .asSequence()
                .mapNotNull { ((it as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull }
                .filter { it.isNotBlank() && it.length <= 255 }
                .distinct()
                .take(512)
                .toList()
        (get("api/tags")?.get("models") as? JsonArray)?.let {
            return Peer(ProviderKind.Ollama, names(it, "name"), null)
        }
        val props = get("props")
        val llama =
            props != null && ("default_generation_settings" in props || "model_path" in props)
        val list = get("v1/models")?.get("data") as? JsonArray
        val models =
            list
                ?.let { names(it, "id") }
                .orEmpty()
                .ifEmpty {
                    if (llama)
                        listOfNotNull(
                            (props["model_path"] as? JsonPrimitive)
                                ?.content
                                ?.substringAfterLast('/')
                        )
                    else emptyList()
                }
        return if (llama || list != null)
            Peer(
                if (llama) ProviderKind.LlamaCpp else ProviderKind.OpenAiCompatible,
                models,
                (props?.get("default_generation_settings") as? JsonObject)
                    ?.get("n_ctx")
                    ?.jsonPrimitive
                    ?.intOrNull,
            )
        else null
    }
}

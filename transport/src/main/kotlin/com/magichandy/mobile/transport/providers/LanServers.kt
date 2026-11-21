package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ServerCheck
import com.magichandy.mobile.core.api.ServerCheckFailure
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.ModelServers
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.ServerAssessment
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.transport.http.boundedText
import com.magichandy.mobile.transport.http.exchange
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

public class LanServers(private val client: OkHttpClient) : ModelServers {
    override suspend fun check(
        address: String,
        model: String?,
        level: CapabilityLevel?,
    ): ServerCheck = inspect(address, model, level)

    override suspend fun verify(
        address: String,
        model: String,
        level: CapabilityLevel,
    ): ServerAssessment {
        val result = inspect(address, model, level)
        return ServerAssessment(
            result,
            (result as? ServerCheck.Found)?.testFailure == ServerCheckFailure.ProviderRefused,
        )
    }

    private suspend fun inspect(
        address: String,
        model: String?,
        level: CapabilityLevel?,
    ): ServerCheck {
        val base =
            serverAddress(address)
                ?: return ServerCheck.Failed(address, ServerCheckFailure.InvalidAddress)
        val http = originClient(client, base)
        return try {
            val metadata = http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
            val peer =
                detect(metadata, base)
                    ?: return ServerCheck.Failed(address, ServerCheckFailure.NotAModelServer)
            if (peer.models.isEmpty())
                return ServerCheck.Failed(address, ServerCheckFailure.NoModels)
            if (model != null && model !in peer.models)
                return ServerCheck.Failed(address, ServerCheckFailure.ModelUnavailable)
            val selected = model ?: peer.models.first()
            val known =
                when {
                    selected.contains("gemma", true) && selected.contains("12b", true) ->
                        CapabilityLevel.Intermediate
                    selected.contains("gemma", true) && selected.contains("e4b", true) ->
                        CapabilityLevel.Simple
                    else -> null
                }
            val suggested = known ?: CapabilityLevel.Simple
            val needed = contextFloor(level ?: suggested)
            val start = TimeSource.Monotonic.markNow()
            // A cold model may take longer to load than a metadata endpoint takes to answer.
            val inference = http.newBuilder().callTimeout(25, TimeUnit.SECONDS).build()
            val provider = localProvider(inference, base, peer.kind, selected, needed)
            val failure = readinessFailure(provider.complete(readinessRequest))
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
                if (failure == null) start.elapsedNow() else null,
                failure,
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
            model.contextTokens.takeIf { it > 0 } ?: contextFloor(model.info.capability),
        )
    }

    private fun contextFloor(level: CapabilityLevel): Int =
        when (level) {
            CapabilityLevel.Simple -> 16384
            CapabilityLevel.Intermediate -> 32768
            CapabilityLevel.Advanced -> 65536
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
                .mapNotNull {
                    ((it as? JsonObject)?.get(key) as? JsonPrimitive)
                        ?.takeIf { name -> name.isString }
                        ?.contentOrNull
                }
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
                                ?.takeIf { it.isString }
                                ?.contentOrNull
                                ?.substringAfterLast('/')
                                ?.substringAfterLast('\\')
                                ?.takeIf { it.isNotBlank() && it.length <= 255 }
                        )
                    else emptyList()
                }
        return if (llama || list != null)
            Peer(
                if (llama) ProviderKind.LlamaCpp else ProviderKind.OpenAiCompatible,
                models,
                ((props?.get("default_generation_settings") as? JsonObject)?.get("n_ctx")
                        as? JsonPrimitive)
                    ?.intOrNull
                    ?.takeIf { it > 0 },
            )
        else null
    }
}

// Readiness exercises the same structured streaming request as chat, without sending chat data.
private val readinessReply = buildJsonObject { put("ready", true) }
private val readinessRequest =
    ModelRequest(
        messages = listOf(TextMessage(TextRole.User, "Return the JSON object $readinessReply.")),
        schema =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("ready") { put("type", "boolean") } }
                putJsonArray("required") { add("ready") }
                put("additionalProperties", false)
            },
        name = "readiness",
        outputAllowance = 32,
    )

private fun readinessFailure(reply: TextResult): ServerCheckFailure? =
    when (reply) {
        TextResult.Refused -> ServerCheckFailure.ProviderRefused
        is TextResult.Completed -> {
            val result =
                try {
                    Json.parseToJsonElement(reply.text)
                } catch (_: IllegalArgumentException) {
                    null
                }
            if (result == readinessReply) null else ServerCheckFailure.IncompleteReply
        }
        TextResult.Interrupted,
        is TextResult.Failed -> ServerCheckFailure.IncompleteReply
    }

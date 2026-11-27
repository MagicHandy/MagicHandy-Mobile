package com.magichandy.mobile.ui.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ReasoningEffort
import com.magichandy.mobile.core.api.ReasoningEffort.ExtraHigh
import com.magichandy.mobile.core.api.ReasoningEffort.High
import com.magichandy.mobile.core.api.ReasoningEffort.Low
import com.magichandy.mobile.core.api.ReasoningEffort.Max
import com.magichandy.mobile.core.api.ReasoningEffort.Medium
import com.magichandy.mobile.core.api.ReasoningOptions
import com.magichandy.mobile.core.api.RouterCatalog
import com.magichandy.mobile.core.api.RouterModel
import com.magichandy.mobile.ui.CoreCalls
import com.magichandy.mobile.ui.components.SheetHeader
import com.magichandy.mobile.ui.components.SheetOverlay
import com.magichandy.mobile.ui.models.ModelExits
import com.magichandy.mobile.ui.models.ModelsScreen
import com.magichandy.mobile.ui.models.OpenRouterScreen
import com.magichandy.mobile.ui.models.RouterModelPicker
import com.magichandy.mobile.ui.settings.ReasoningPage

/** OpenRouter's catalog as these previews show it: real rows from 2026-11-27. */
internal object SampleRouter {
    private val everyLevel = listOf(Low, Medium, High, ExtraHigh, Max)

    val deepSeek =
        RouterModel(
            "deepseek/deepseek-v4.1-flash-1017",
            "DeepSeek V4.1 Flash 1017",
            1_048_576,
            ReasoningOptions(listOf(Low, High, Max), isRequired = false, defaultLevel = High),
            36_000,
            500_000,
        )
    val glm =
        RouterModel(
            "z-ai/glm-5.3-flash",
            "GLM 5.3 Flash",
            1_048_576,
            ReasoningOptions(listOf(Low, High, Max), isRequired = true, defaultLevel = Max),
            150_000,
            500_000,
        )
    val catalog =
        RouterCatalog.Loaded(
            listOf(
                deepSeek,
                RouterModel(
                    "tencent/hy4-flash",
                    "Hy4 flash",
                    1_048_576,
                    ReasoningOptions(ReasoningEffort.entries, isRequired = false),
                    35_000,
                    140_000,
                ),
                RouterModel(
                    "deepseek/deepseek-v4.1-flash",
                    "DeepSeek V4.1 Flash",
                    1_048_576,
                    ReasoningOptions(
                        listOf(Low, High, Max),
                        isRequired = false,
                        defaultLevel = High,
                    ),
                    112_000,
                    336_000,
                ),
                glm,
                RouterModel(
                    "openai/gpt-6-luna",
                    "GPT-6 Luna",
                    1_050_000,
                    ReasoningOptions(everyLevel, isRequired = false, defaultLevel = Medium),
                    50_000,
                    250_000,
                ),
                RouterModel(
                    "anthropic/claude-opus-5.5",
                    "Claude Opus 5.5",
                    1_000_000,
                    ReasoningOptions(everyLevel, isRequired = true, defaultLevel = High),
                    4_000_000,
                    20_000_000,
                ),
                RouterModel(
                    "openai/gpt-6.1-terra",
                    "GPT-6.1 Terra",
                    1_050_000,
                    ReasoningOptions(everyLevel, isRequired = false, defaultLevel = Medium),
                    500_000,
                    2_500_000,
                ),
            )
        )

    fun connection(
        model: RouterModel,
        isReasoningOn: Boolean,
        level: ReasoningEffort?,
        status: ConnectionStatus = ConnectionStatus.Ready,
    ) =
        ModelConnection(
            ConnectionId("router-" + model.id),
            "OpenRouter",
            ProviderKind.OpenRouter,
            model.id,
            model.name,
            CapabilityLevel.Advanced,
            isReasoningOn,
            true,
            status,
            level,
        )

    /** Playing, with [chat] saved and chosen for Chat and the catalog loaded. */
    fun state(chat: ModelConnection): AppState =
        SampleStates.playing.let {
            it.copy(
                models =
                    it.models.copy(
                        connections = it.models.connections + chat,
                        chatConnection = chat.id,
                        routerCatalog = catalog,
                    )
            )
        }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun OpenRouterEdit(@PreviewParameter(PaletteProvider::class) palette: Palette) {
    val connection = SampleRouter.connection(SampleRouter.deepSeek, false, null)
    InFrame(palette) {
        OpenRouterScreen(
            SampleRouter.state(connection).models,
            CoreCalls.None.submit,
            onIntent = {},
            onBack = {},
            editing = connection,
        )
    }
}

@Preview(widthDp = 390, heightDp = 844, fontScale = 2f)
@Composable
private fun OpenRouterEditLargeText() {
    val connection = SampleRouter.connection(SampleRouter.deepSeek, true, Low)
    InFrame(Palette.SteelAzure) {
        OpenRouterScreen(
            SampleRouter.state(connection).models,
            CoreCalls.None.submit,
            onIntent = {},
            onBack = {},
            editing = connection,
        )
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun OpenRouterRequired(@PreviewParameter(PaletteProvider::class) palette: Palette) {
    val connection = SampleRouter.connection(SampleRouter.glm, true, Low)
    InFrame(palette) {
        OpenRouterScreen(
            SampleRouter.state(connection).models,
            CoreCalls.None.submit,
            onIntent = {},
            onBack = {},
            editing = connection,
        )
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun OpenRouterModels(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        RouterModelPicker(
            SampleRouter.catalog,
            selected = SampleRouter.deepSeek.id,
            onChoose = {},
            onRetry = {},
            onBack = {},
        )
    }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun OpenRouterModelsOffline(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        RouterModelPicker(
            RouterCatalog.Failed,
            selected = "",
            onChoose = {},
            onRetry = {},
            onBack = {},
        )
    }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun ModelsOpenRouter(@PreviewParameter(PaletteProvider::class) palette: Palette) {
    val offline =
        SampleRouter.connection(SampleRouter.deepSeek, false, null, ConnectionStatus.Unreachable)
    InFrame(palette) {
        ModelsScreen(
            SampleRouter.state(offline),
            submit = CoreCalls.None.submit,
            exits = ModelExits(back = {}, addServer = {}, addOpenRouter = {}, addChatGpt = {}),
        )
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun ReasoningSheet(@PreviewParameter(PaletteProvider::class) palette: Palette) {
    val connection = SampleRouter.connection(SampleRouter.glm, true, Low)
    InFrame(palette) {
        Box(Modifier.fillMaxSize()) {
            SheetOverlay(title = "Reasoning", onDismiss = {}) {
                SheetHeader(title = "Reasoning", onClose = {})
                ReasoningPage(
                    SampleRouter.state(connection),
                    onIntent = {},
                    submit = CoreCalls.None.submit,
                )
            }
        }
    }
}

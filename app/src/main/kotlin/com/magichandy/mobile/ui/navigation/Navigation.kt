package com.magichandy.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.PersonaId

/**
 * The app's routes (plan §14.2). Chat and Devices are the two destinations; everything else is a
 * subpage opened from them. Before you start and Run interrupted are shown by the root instead of
 * being pushed, because they gate the whole app.
 */
sealed interface Route {
    /** A stable string form, so the back stack survives activity recreation. */
    val key: String

    data object Chat : Route {
        override val key: String = "chat"
    }

    data object Devices : Route {
        override val key: String = "devices"
    }

    data object Live : Route {
        override val key: String = "live"
    }

    data class DeviceDetail(val device: DeviceId) : Route {
        override val key: String
            get() = "device:${device.value}"
    }

    data class AddDevice(val family: DeviceFamily) : Route {
        override val key: String
            get() = "add:${family.name}"
    }

    data object AppSettings : Route {
        override val key: String = "app-settings"
    }

    data object Models : Route {
        override val key: String = "models"
    }

    /** Settings › Voice (board V-VoiceSettings), opened from App settings. */
    data object VoiceSettings : Route {
        override val key: String = "voice"
    }

    data object AddServer : Route {
        override val key: String = "add-server"
    }

    data object OpenRouterSetup : Route {
        override val key: String = "openrouter"
    }

    /** A saved OpenRouter connection's page (board V-OpenRouterSettings), opened from Models. */
    data class OpenRouterConnection(val connection: ConnectionId) : Route {
        override val key: String
            get() = "openrouter:${connection.value}"
    }

    data object ChatGptSetup : Route {
        override val key: String = "chatgpt"
    }

    /**
     * One step of first-run setup, 1 to 4 (boards V-Onboarding, V-SetupModel, V-SetupCompanion and
     * V-SetupReady). The steps are routes, so Back walks back through them.
     */
    data class Setup(val step: Int) : Route {
        override val key: String
            get() = "setup:$step"
    }

    /** Choosing where Chat sends messages, outside setup, such as from Chat with no model. */
    data object ChooseAi : Route {
        override val key: String = "choose-ai"
    }

    data object Personas : Route {
        override val key: String = "personas"
    }

    /** One persona's editor, or a new persona's when [persona] is null. */
    data class PersonaEditor(val persona: PersonaId?) : Route {
        override val key: String
            get() = persona?.let { "persona:${it.value}" } ?: NEW_PERSONA
    }

    companion object {
        private val simple =
            listOf(
                    Chat,
                    Devices,
                    Live,
                    AppSettings,
                    Models,
                    VoiceSettings,
                    AddServer,
                    OpenRouterSetup,
                    ChatGptSetup,
                    ChooseAi,
                    Personas,
                )
                .associateBy { it.key }

        private const val NEW_PERSONA = "persona-new"

        /** The route for [key], or null when it no longer exists. */
        fun fromKey(key: String): Route? =
            simple[key]
                ?: when {
                    key.startsWith("device:") -> DeviceDetail(DeviceId(key.removePrefix("device:")))
                    key.startsWith("add:") ->
                        DeviceFamily.entries
                            .firstOrNull { it.name == key.removePrefix("add:") }
                            ?.let(::AddDevice)
                    key.startsWith("setup:") ->
                        key.removePrefix("setup:")
                            .toIntOrNull()
                            ?.takeIf { it in 1..4 }
                            ?.let(::Setup)
                    key.startsWith("openrouter:") ->
                        OpenRouterConnection(ConnectionId(key.removePrefix("openrouter:")))
                    key == NEW_PERSONA -> PersonaEditor(null)
                    key.startsWith("persona:") ->
                        PersonaEditor(PersonaId(key.removePrefix("persona:")))
                    else -> null
                }
    }
}

/** The pages of the in-window sheets. Pages after the first in a stack show Back. */
enum class SheetPage {
    Chats,
    Settings,
    MotionMode,
    Style,
    Autopilot,
    PlannerModel,
    Persona,
    ChatModel,
    RefusalFallback,
    Memory,
    DeviceType,

    /** Voice input's first tap (V-VoiceSetup). */
    VoiceSetup,

    /** The speech model picker on first use, over the first tap's sheet (V-VoiceModelPick). */
    VoicePicker,

    /** The speech model picker from Settings › Voice (V-VoiceModelSwitch). */
    VoiceModel,

    /** The chat model's OpenRouter reasoning, from Chat model (V-OpenRouterReasoning). */
    Reasoning,
}

/** The back stack and the open sheet. Back closes a sheet before it pops a route. */
@Stable
class AppNavigator(stack: List<Route>, sheets: List<SheetPage>) {
    var stack: List<Route> by mutableStateOf(stack)
        private set

    var sheets: List<SheetPage> by mutableStateOf(sheets)
        private set

    val current: Route
        get() = stack.last()

    val sheet: SheetPage?
        get() = sheets.lastOrNull()

    /** True on Chat and Devices, where the tabs show. */
    val isAtDestination: Boolean
        get() = stack.size == 1

    /** The route under the current one, or null at a destination. */
    val previous: Route?
        get() = stack.getOrNull(stack.size - 2)

    fun push(route: Route) {
        sheets = emptyList()
        stack = stack + route
    }

    /** Replaces the whole back stack, as when setup starts or ends. */
    fun resetTo(routes: List<Route>) {
        sheets = emptyList()
        stack = routes
    }

    /** Pops back to the last [route] on the stack, if it's there. */
    fun popTo(route: Route) {
        val index = stack.lastIndexOf(route)
        if (index >= 0) {
            sheets = emptyList()
            stack = stack.take(index + 1)
        }
    }

    /** Pops one route. Returns false at a destination, so the system can handle Back. */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack = stack.dropLast(1)
        return true
    }

    fun selectDestination(route: Route) {
        sheets = emptyList()
        stack = listOf(route)
    }

    /** Shows Live above Chat, as the chat deck opens it, and closes any sheet. */
    fun openLive() {
        sheets = emptyList()
        stack = listOf(Route.Chat, Route.Live)
    }

    fun openSheet(page: SheetPage) {
        sheets = listOf(page)
    }

    fun pushSheet(page: SheetPage) {
        sheets = sheets + page
    }

    /** Back inside a sheet: the previous page, or closing the sheet from its first page. */
    fun sheetBack() {
        sheets = sheets.dropLast(1)
    }

    fun closeSheet() {
        sheets = emptyList()
    }

    companion object {
        /** Saves the stack and sheet pages as strings. */
        val Saver =
            listSaver<AppNavigator, String>(
                save = { nav ->
                    listOf(nav.sheets.joinToString(",") { it.name }) + nav.stack.map { it.key }
                },
                restore = { saved ->
                    val sheets =
                        saved.first().split(",").mapNotNull { name ->
                            SheetPage.entries.firstOrNull { it.name == name }
                        }
                    val stack = saved.drop(1).mapNotNull(Route::fromKey)
                    AppNavigator(stack.ifEmpty { listOf(Route.Chat) }, sheets)
                },
            )
    }
}

/** An [AppNavigator] that survives configuration changes. */
@Composable
fun rememberAppNavigator(): AppNavigator =
    rememberSaveable(saver = AppNavigator.Saver) { AppNavigator(listOf(Route.Chat), emptyList()) }

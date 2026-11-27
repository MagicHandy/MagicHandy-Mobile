package com.magichandy.mobile.platform.storage

import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.ChatSummary
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.EntryId
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.ports.ChatStore
import com.magichandy.mobile.core.ports.DeviceRecords
import com.magichandy.mobile.core.ports.ModelCatalog
import com.magichandy.mobile.core.ports.ModelStore
import com.magichandy.mobile.core.ports.PersonaChange
import com.magichandy.mobile.core.ports.PreferenceEdit
import com.magichandy.mobile.core.ports.SavedDevice
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.SettingsDocument
import com.magichandy.mobile.core.ports.SettingsStore
import com.magichandy.mobile.core.ports.after
import com.magichandy.mobile.core.ports.builtInPersona
import com.magichandy.mobile.core.ports.newMemories
import com.magichandy.mobile.core.ports.personaNameOrder
import com.magichandy.mobile.core.ports.withFields
import com.magichandy.mobile.core.ports.withPersonaEdit
import java.io.IOException
import java.util.concurrent.Callable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val codec = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal class RoomStores(
    private val db: PlatformDatabase,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = db.records()
    private val owner = Mutex()
    private val prefs = MutableStateFlow(SettingsDocument())
    private val catalog = MutableStateFlow(ModelCatalog())
    private val deviceRows = MutableStateFlow(emptyList<SavedDevice>())
    private val pages = RoomChatPages(dao, codec)
    private val ready =
        scope.async(io) {
            owner.withLock {
                try {
                    prefs.value = loadSettingsWithPersonas()
                    catalog.value = load("models", ModelCatalog())
                    deviceRows.value = load("devices", emptyList())
                    pages.restore()
                } catch (failure: Exception) {
                    throw IOException("Database initialization failed", failure)
                }
            }
        }

    suspend fun awaitReady() {
        ready.await()
    }

    private inline fun <reified T> load(name: String, default: T): T =
        dao.get(name)?.let { codec.decodeFromString<T>(it) } ?: default

    private inline fun <reified T> put(name: String, value: T) {
        dao.put(Document(name, codec.encodeToString(value)))
    }

    private suspend fun <T> serialized(action: () -> T): T =
        withContext(io) {
            ready.await()
            owner.withLock {
                try {
                    action()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (denied: Denied) {
                    throw denied
                } catch (e: Exception) {
                    throw IOException("Database operation failed", e)
                }
            }
        }

    private fun <T> transaction(action: () -> T): T = db.runInTransaction(Callable { action() })

    private class Denied : RuntimeException()

    private fun loadSettingsWithPersonas(): SettingsDocument =
        load("settings", SettingsDocument())
            .copy(
                customPersonas =
                    dao.personas()
                        .map {
                            PersonaRef(
                                PersonaId(it.id),
                                it.name,
                                "",
                                codec.decodeFromString(it.personality),
                            )
                        }
                        .sortedWith(personaNameOrder)
            )

    val settings =
        object : SettingsStore {
            override val state = prefs.asStateFlow()

            override suspend fun editPersonas(
                edit: (SettingsDocument) -> PersonaChange
            ): FormResult = serialized {
                var deleted: PersonaId? = null
                val next = transaction {
                    val old = loadSettingsWithPersonas()
                    when (val change = edit(old)) {
                        is PersonaChange.Rejected ->
                            return@transaction null to FormResult.Rejected(change.reason)
                        is PersonaChange.Edit -> {
                            change.save?.let { row ->
                                dao.savePersona(
                                    PersonaRow(
                                        row.id.value,
                                        row.name,
                                        codec.encodeToString(row.personality),
                                    )
                                )
                            }
                            change.delete?.let {
                                dao.deletePersona(it.value)
                                dao.forget(it.value)
                                deleted = it
                            }
                            val saved = old.withPersonaEdit(change)
                            if (change.fields.isNotEmpty()) put("settings", saved)
                            saved to FormResult.Accepted
                        }
                    }
                }
                next.first?.let { prefs.value = it }
                deleted?.let(pages::forget)
                next.second
            }

            override suspend fun patch(baseRevision: Long, edits: List<PreferenceEdit>): Boolean =
                serialized {
                    val next =
                        transaction {
                            val old = load("settings", SettingsDocument())
                            if (
                                baseRevision !in 0..old.revision ||
                                    edits.any { (old.changedAt[it.field] ?: 0) > baseRevision }
                            )
                                return@transaction null
                            if (edits.isEmpty()) return@transaction old
                            old.withFields(edits).also { put("settings", it) }
                        } ?: return@serialized false
                    prefs.value = next.copy(customPersonas = prefs.value.customPersonas)
                    true
                }
        }

    private suspend fun modelsEdit(edit: (ModelCatalog) -> ModelCatalog) {
        serialized {
            catalog.value = transaction {
                edit(load("models", ModelCatalog())).also { put("models", it) }
            }
        }
    }

    suspend fun requireChatGptSignIn() = modelsEdit { catalog ->
        val id = ConnectionId("chatgpt")
        val row = catalog.models[id] ?: return@modelsEdit catalog
        catalog.copy(
            models =
                catalog.models +
                    (id to row.copy(info = row.info.copy(status = ConnectionStatus.NeedsSignIn)))
        )
    }

    val models =
        object : ModelStore {
            override val state = catalog.asStateFlow()

            override suspend fun save(model: SavedModel) = save(model, selectForChat = false)

            override suspend fun save(model: SavedModel, selectForChat: Boolean) = modelsEdit {
                it.copy(
                    models = it.models + (model.info.id to model),
                    chat = if (selectForChat) model.info.id else it.chat ?: model.info.id,
                )
            }

            override suspend fun remove(id: ConnectionId) = modelsEdit {
                it.copy(
                    models = it.models - id,
                    chat = it.chat?.takeUnless { r -> r == id },
                    planner = it.planner?.takeUnless { r -> r == id },
                )
            }

            override suspend fun bindChat(id: ConnectionId) = modelsEdit {
                if (id in it.models) it.copy(chat = id) else it
            }

            override suspend fun bindPlanner(id: ConnectionId?) = modelsEdit {
                if (id == null || id in it.models) it.copy(planner = id) else it
            }
        }

    private suspend fun deviceEdit(edit: (List<SavedDevice>) -> List<SavedDevice>) {
        serialized {
            deviceRows.value = transaction {
                edit(load("devices", emptyList())).also { put("devices", it) }
            }
        }
    }

    val devices =
        object : DeviceRecords {
            override val rows = deviceRows.asStateFlow()

            override suspend fun save(row: SavedDevice) = deviceEdit { list ->
                if (list.none { it.ref.id == row.ref.id }) list + row
                else list.map { if (it.ref.id == row.ref.id) row else it }
            }

            override suspend fun update(id: DeviceId, transform: (SavedDevice) -> SavedDevice) =
                deviceEdit { rows ->
                    rows.map { if (it.ref.id == id) transform(it) else it }
                }

            override suspend fun remove(id: DeviceId) = deviceEdit { list ->
                list.filterNot { it.ref.id == id }
            }
        }

    val chats =
        object : ChatStore {
            override val state = pages.state

            override suspend fun loadPage(chat: ChatId) = serialized { pages.open(chat) }

            override suspend fun opening(chat: ChatId, limit: Int): List<ChatEntry> = serialized {
                require(limit >= 0)
                dao.opening(chat.value, limit).map { codec.decodeFromString<ChatEntry>(it) }
            }

            override suspend fun loadOlder(chat: ChatId, oldest: EntryId) = serialized {
                pages.older(chat, oldest)
            }

            override suspend fun trimPages() = serialized { pages.trim() }

            override suspend fun create(chat: ChatSummary) {
                serialized {
                    dao.create(
                        Header(
                            chat.id.value,
                            codec.encodeToString(chat),
                            chat.lastActivity.toEpochMilliseconds(),
                        )
                    )
                    pages.created(chat)
                }
            }

            override suspend fun append(
                chat: ChatId,
                entry: ChatEntry,
                persona: PersonaId,
                memories: List<String>,
                admitted: () -> Boolean,
            ): Boolean {
                return try {
                    serialized {
                        val row =
                            Entry(
                                entryId = entry.id.value,
                                chatId = chat.value,
                                payload = codec.encodeToString(entry),
                            )
                        var inserted: ChatSummary? = null
                        var added = emptyList<String>()
                        val saved = transaction {
                            val head = dao.header(chat.value) ?: return@transaction false
                            if (dao.append(row) == -1L) {
                                val existing = dao.entry(row.entryId)
                                return@transaction existing?.chatId == row.chatId &&
                                    existing.payload == row.payload
                            }
                            val summary =
                                codec.decodeFromString<ChatSummary>(head.payload).let {
                                    it.after(entry)
                                }
                            dao.update(
                                head.copy(
                                    payload = codec.encodeToString(summary),
                                    activity = entry.at.toEpochMilliseconds(),
                                )
                            )
                            if (
                                memories.isNotEmpty() &&
                                    (persona == builtInPersona.id || dao.hasPersona(persona.value))
                            ) {
                                added = newMemories(dao.memoryFor(persona.value), memories)
                                dao.remember(added.map { Memory(persona.value, it) })
                            }
                            inserted = summary
                            if (!admitted()) throw Denied()
                            true
                        }
                        inserted?.let { pages.publishAppend(it, entry, persona, added) }
                        saved
                    }
                } catch (_: Denied) {
                    false
                }
            }

            override suspend fun clearMemory(persona: PersonaId) {
                serialized {
                    dao.forget(persona.value)
                    pages.forget(persona)
                }
            }

            override suspend fun clearAll() {
                serialized {
                    transaction {
                        dao.clearMessages()
                        dao.clearHeaders()
                        dao.clearMemories()
                    }
                    pages.clear()
                }
            }
        }
}

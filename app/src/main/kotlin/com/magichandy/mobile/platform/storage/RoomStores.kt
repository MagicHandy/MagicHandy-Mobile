package com.magichandy.mobile.platform.storage

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import java.io.IOException
import java.util.concurrent.Callable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val archive = MutableStateFlow(ChatArchive())
    private var page: ChatId? = null
    private var pageLimit = 513
    private val ready =
        scope.async(io) {
            owner.withLock {
                try {
                    prefs.value = load("settings", SettingsDocument())
                    catalog.value = load("models", ModelCatalog())
                    deviceRows.value = load("devices", emptyList())
                    page = dao.headers().firstOrNull()?.let { ChatId(it.id) }
                    reloadChat()
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

    val settings =
        object : SettingsStore {
            override val state = prefs.asStateFlow()

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
                            val r = old.revision + 1
                            SettingsDocument(
                                    r,
                                    edits.fold(old.values) { s, e -> e.apply(s) },
                                    old.changedAt + edits.associate { it.field to r },
                                )
                                .also { put("settings", it) }
                        } ?: return@serialized false
                    prefs.value = next
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

    val models =
        object : ModelStore {
            override val state = catalog.asStateFlow()

            override suspend fun save(model: SavedModel) = modelsEdit {
                it.copy(
                    models = it.models + (model.info.id to model),
                    chat = it.chat ?: model.info.id,
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

            override suspend fun remove(id: DeviceId) = deviceEdit { list ->
                list.filterNot { it.ref.id == id }
            }
        }

    private fun reloadChat() {
        val headers = dao.headers()
        val chats = headers.associate { h ->
            val summary = codec.decodeFromString<ChatSummary>(h.payload)
            val entries =
                if (summary.id == page)
                    dao.page(h.id, pageLimit).asReversed().map {
                        codec.decodeFromString<ChatEntry>(it)
                    }
                else emptyList()
            summary.id to SavedChat(summary, entries)
        }
        archive.value =
            ChatArchive(chats, dao.memories().groupBy({ PersonaId(it.persona) }, { it.text }))
    }

    private fun publishAppend(
        summary: ChatSummary,
        entry: ChatEntry,
        persona: PersonaId,
        memories: List<String>,
    ) {
        val before = archive.value
        val old = before.chats[summary.id]?.messages.orEmpty()
        val visible = if (summary.id == page) (old + entry).takeLast(pageLimit) else emptyList()
        val memory =
            if (memories.isEmpty()) before.memory
            else
                before.memory +
                    (persona to (before.memory[persona].orEmpty() + memories).distinct())
        archive.value =
            before.copy(
                chats = before.chats + (summary.id to SavedChat(summary, visible)),
                memory = memory,
            )
    }

    val chats =
        object : ChatStore {
            override val state = archive.asStateFlow()

            override suspend fun loadPage(chat: ChatId) {
                serialized {
                    if (page != chat || pageLimit != 513) {
                        page = chat
                        pageLimit = 513
                        reloadChat()
                    }
                }
            }

            override suspend fun trimPages() {
                serialized {
                    pageLimit = 64
                    val current = archive.value
                    val id = page ?: return@serialized
                    val chat = current.chats[id] ?: return@serialized
                    archive.value =
                        current.copy(
                            chats =
                                current.chats +
                                    (id to chat.copy(messages = chat.messages.takeLast(pageLimit)))
                        )
                }
            }

            override suspend fun create(chat: ChatSummary) {
                serialized {
                    dao.create(
                        Header(
                            chat.id.value,
                            codec.encodeToString(chat),
                            chat.lastActivity.toEpochMilliseconds(),
                        )
                    )
                    pageLimit = 513
                    page = chat.id
                    reloadChat()
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
                                codec
                                    .decodeFromString<ChatSummary>(head.payload)
                                    .copy(lastActivity = entry.at)
                            dao.update(
                                head.copy(
                                    payload = codec.encodeToString(summary),
                                    activity = entry.at.toEpochMilliseconds(),
                                )
                            )
                            if (memories.isNotEmpty()) {
                                added = newMemories(dao.memoryFor(persona.value), memories)
                                dao.remember(added.map { Memory(persona.value, it) })
                            }
                            inserted = summary
                            if (!admitted()) throw Denied()
                            true
                        }
                        inserted?.let { publishAppend(it, entry, persona, added) }
                        saved
                    }
                } catch (_: Denied) {
                    false
                }
            }

            override suspend fun clearMemory(persona: PersonaId) {
                serialized {
                    dao.forget(persona.value)
                    archive.value = archive.value.copy(memory = archive.value.memory - persona)
                }
            }

            override suspend fun clearAll() {
                serialized {
                    transaction {
                        dao.clearMessages()
                        dao.clearHeaders()
                        dao.clearMemories()
                    }
                    page = null
                    archive.value = ChatArchive()
                }
            }
        }
}

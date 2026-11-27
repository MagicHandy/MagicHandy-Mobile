package com.magichandy.mobile.platform

import android.content.Context
import androidx.room.Room
import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.ChatSummary
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.EntryId
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.PreferenceField
import com.magichandy.mobile.core.ports.RunId
import com.magichandy.mobile.core.ports.RunIntent
import com.magichandy.mobile.core.ports.RunJournal
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.SecretHandle
import com.magichandy.mobile.core.ports.TilePresence
import com.magichandy.mobile.core.ports.recentModelEntries
import com.magichandy.mobile.platform.storage.AndroidVault
import com.magichandy.mobile.platform.storage.AtomicBytes
import com.magichandy.mobile.platform.storage.Document
import com.magichandy.mobile.platform.storage.FileJournal
import com.magichandy.mobile.platform.storage.Memory
import com.magichandy.mobile.platform.storage.PlatformDatabase
import com.magichandy.mobile.platform.storage.RoomStores
import com.magichandy.mobile.platform.storage.RotatingDiagnostics
import java.io.File
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
internal class StorageTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val at = Instant.fromEpochMilliseconds(123)

    @org.junit.Test
    fun freshAcknowledgedButUnfinishedSetupSurvivesRoomReopen() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, PlatformDatabase::class.java).build()
        try {
            suspend fun reopen() = RoomStores(db, scope).also { it.awaitReady() }
            val fresh = reopen()
            assertFalse(fresh.settings.state.value.values.isSetupDone)
            assertTrue(fresh.settings.patch(0, listOf(PreferenceField.AdultsOnly.edit(true))))
            val incomplete = reopen()
            assertTrue(incomplete.settings.state.value.values.adultsOnly)
            assertFalse(incomplete.settings.state.value.values.isSetupDone)
            assertTrue(incomplete.settings.patch(1, listOf(PreferenceField.SetupDone.edit(true))))
            incomplete.chats.clearAll()
            assertTrue(reopen().settings.state.value.values.isSetupDone)
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @org.junit.Test
    fun savingAModelForChatReplacesAnExistingDefaultAcrossRoomReopen() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, PlatformDatabase::class.java).build()
        try {
            suspend fun reopen() = RoomStores(db, scope).also { it.awaitReady() }
            val first =
                ModelConnection(
                    ConnectionId("first"),
                    "First",
                    ProviderKind.OpenRouter,
                    "fixture/first",
                    "first",
                    CapabilityLevel.Advanced,
                    false,
                    true,
                    ConnectionStatus.Ready,
                )
            val second = first.copy(id = ConnectionId("second"), name = "Second")
            val third = first.copy(id = ConnectionId("third"), name = "Third")
            val stores = reopen()
            stores.models.save(SavedModel(first))
            stores.models.save(SavedModel(second))
            assertEquals(first.id, stores.models.state.value.chat)
            stores.models.save(SavedModel(third), selectForChat = true)
            assertEquals(third.id, stores.models.state.value.chat)
            val reloaded = reopen()
            assertEquals(third.id, reloaded.models.state.value.chat)
            assertEquals(
                setOf(first.id, second.id, third.id),
                reloaded.models.state.value.models.keys,
            )
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @org.junit.Test
    fun savedLowlightLoadsAsOledBlackAndTheNextPatchPersistsTheCurrentName() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, PlatformDatabase::class.java).build()
        try {
            withContext(Dispatchers.IO) {
                db.records()
                    .put(
                        Document(
                            "settings",
                            """{"revision":4,"values":{"palette":"Lowlight","adultsOnly":true},"changedAt":{"palette":4}}""",
                        )
                    )
            }
            val stores = RoomStores(db, scope)
            stores.awaitReady()
            assertEquals(Palette.OledBlack, stores.settings.state.value.values.palette)
            assertEquals(4L, stores.settings.state.value.revision)
            assertTrue(stores.settings.state.value.values.adultsOnly)
            assertTrue(stores.settings.patch(4, listOf(PreferenceField.Memory.edit(true))))
            val saved = withContext(Dispatchers.IO) { requireNotNull(db.records().get("settings")) }
            assertTrue("OledBlack" in saved)
            assertFalse("Lowlight" in saved)
            assertTrue(
                stores.settings.patch(
                    5,
                    listOf(PreferenceField.PaletteChoice.edit(Palette.Lowlight)),
                )
            )
            assertEquals(Palette.OledBlack, stores.settings.state.value.values.palette)
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @org.junit.Test
    fun tilePresenceSurvivesReopenAndStartsUnknown() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, PlatformDatabase::class.java).build()
        try {
            val first = RoomStores(db, scope)
            first.awaitReady()
            assertEquals(TilePresence.Unknown, first.settings.state.value.values.stopTile)
            assertTrue(
                first.settings.patch(0, listOf(PreferenceField.StopTile.edit(TilePresence.Added)))
            )
            val second = RoomStores(db, scope)
            second.awaitReady()
            assertEquals(TilePresence.Added, second.settings.state.value.values.stopTile)
            assertTrue(
                second.settings.patch(
                    1,
                    listOf(PreferenceField.StopTile.edit(TilePresence.Removed)),
                )
            )
            val third = RoomStores(db, scope)
            third.awaitReady()
            assertEquals(TilePresence.Removed, third.settings.state.value.values.stopTile)
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @org.junit.Test
    fun shareProviderExposesOnlyDiagnosticsAndDropsUnrecognizedDiskText() = runBlocking {
        val authority = context.packageName + ".diagnostics"
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "synthetic.txt")
        val provider =
            requireNotNull(
                context.packageManager.resolveContentProvider(
                    authority,
                    android.content.pm.PackageManager.GET_META_DATA,
                )
            )
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val xml =
            requireNotNull(
                provider.loadXmlMetaData(
                    context.packageManager,
                    "android.support.FILE_PROVIDER_PATHS",
                )
            )
        val roots = mutableListOf<Pair<String, String>>()
        xml.use {
            while (it.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (it.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && it.depth == 2)
                    roots += it.name to it.getAttributeValue(null, "path")
            }
        }
        assertEquals(listOf("cache-path" to "diagnostics/"), roots)
        // AndroidX compares slash-delimited canonical paths on Android.
        if (File.separatorChar == '/')
            assertEquals(
                authority,
                androidx.core.content.FileProvider.getUriForFile(context, authority, file)
                    .authority,
            )
        assertFailsWith<IllegalArgumentException> {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                authority,
                File(context.cacheDir, "private.txt"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                authority,
                File(context.noBackupFilesDir, "journal"),
            )
        }
        val stored = File(context.cacheDir, "test-ring").apply { mkdirs() }
        File(stored, "events.log")
            .writeText(
                "1 StopRequested\n2 secret-test-token\n3 StopRequested http://127.0.0.1\n4 DeviceReconciled\n"
            )
        val entries = RotatingDiagnostics(stored).recent(10)
        assertEquals(
            listOf(DiagnosticKind.StopRequested, DiagnosticKind.DeviceReconciled),
            entries.map { it.kind },
        )
    }

    @org.junit.Test
    fun settingsAndMessagesCommitAtomically() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, PlatformDatabase::class.java).build()
        try {
            val stores = RoomStores(db, scope)
            assertTrue(stores.settings.patch(0, listOf(PreferenceField.Memory.edit(true))))
            assertTrue(stores.settings.patch(0, listOf(PreferenceField.CoyoteNotes.edit(true))))
            assertFalse(stores.settings.patch(0, listOf(PreferenceField.Memory.edit(false))))
            val chat = ChatId("synthetic")
            val persona = PersonaId("")
            stores.chats.create(ChatSummary(chat, "Session", "MagicHandy", at))
            val m = ChatEntry.Message(EntryId("entry"), at, Author.Persona, "Synthetic.", null)
            assertFalse(stores.chats.append(chat, m, persona, listOf("synthetic memory")) { false })
            assertTrue(stores.chats.state.value.chats.getValue(chat).messages.isEmpty())
            assertTrue(stores.chats.state.value.memory.isEmpty())
            repeat(2) {
                assertTrue(
                    stores.chats.append(chat, m, persona, listOf("synthetic memory")) { true }
                )
            }
            val proposed = List(210) { "Synthetic fact $it" }
            val other = m.copy(id = EntryId("additional"))
            assertTrue(stores.chats.append(chat, other, persona, proposed) { true })
            assertEquals(200, stores.chats.state.value.memory.getValue(persona).size)
            val reopened = RoomStores(db, scope)
            reopened.awaitReady()
            assertEquals(listOf(m, other), reopened.chats.state.value.chats.getValue(chat).messages)
            assertEquals(
                listOf("synthetic memory") + proposed.take(199),
                reopened.chats.state.value.memory[persona],
            )
            assertEquals(2L, reopened.settings.state.value.revision)
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @org.junit.Test
    fun migrationFromVersionOnePreservesTranscriptAndBuildsPagingIndex() = runBlocking {
        withContext(Dispatchers.IO) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val chat = ChatId("old")
            val entry = ChatEntry.Message(EntryId("old-entry"), at, Author.You, "Earlier.", null)
            suspend fun withDatabase(check: suspend (PlatformDatabase) -> Unit) {
                val db = PlatformDatabase.open(context)
                try {
                    check(db)
                } finally {
                    db.close()
                }
            }
            try {
                withDatabase { db ->
                    val stores = RoomStores(db, scope)
                    stores.chats.create(ChatSummary(chat, "Earlier", "MagicHandy", at))
                    assertTrue(
                        stores.chats.append(chat, entry, PersonaId(""), emptyList()) { true }
                    )
                }
                val file = File(context.noBackupFilesDir, "magichandy.db")
                android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, 0).use { db ->
                    db.execSQL("DROP INDEX index_messages_chatId_sequence")
                    db.execSQL("DELETE FROM room_master_table")
                    db.version = 1
                }
                withDatabase { db ->
                    val stores = RoomStores(db, scope)
                    stores.awaitReady()
                    assertEquals(
                        listOf(entry),
                        stores.chats.state.value.chats.getValue(chat).messages,
                    )
                    db.openHelper.readableDatabase.query("PRAGMA index_list(messages)").use { cursor
                        ->
                        val index = cursor.getColumnIndexOrThrow("name")
                        assertTrue(
                            generateSequence {
                                    if (cursor.moveToNext()) cursor.getString(index) else null
                                }
                                .contains("index_messages_chatId_sequence")
                        )
                    }
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @org.junit.Test
    fun trimmingReleasesOnlyTheProjectionAndAnEntryCannotBeReplayedIntoAnotherChat() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, PlatformDatabase::class.java).build()
        try {
            val chats = RoomStores(db, scope).chats
            val a = ChatId("a")
            val b = ChatId("b")
            val persona = PersonaId("")
            chats.create(ChatSummary(b, "Second", "MagicHandy", at))
            chats.create(ChatSummary(a, "First", "MagicHandy", at))
            val entries =
                (0..69).map {
                    ChatEntry.Message(EntryId("id-$it"), at, Author.You, "Synthetic $it", null)
                }
            for (entry in entries) assertTrue(chats.append(a, entry, persona, emptyList()) { true })
            assertFalse(chats.append(b, entries.last(), persona, emptyList()) { true })
            assertFalse(
                chats.append(a, entries.last().copy(text = "different"), persona, emptyList()) {
                    true
                }
            )
            chats.trimPages()
            assertEquals(entries.takeLast(64), chats.state.value.chats.getValue(a).messages)
            chats.loadPage(a)
            assertEquals(entries, chats.state.value.chats.getValue(a).messages)
            assertTrue(chats.state.value.chats.getValue(b).messages.isEmpty())
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @org.junit.Test
    fun diagnosticsRotateAndStoreOnlyTimestampAndCategory() = runBlocking {
        val dir = File(context.cacheDir, "diag")
        val sink = RotatingDiagnostics(dir, 80)
        repeat(30) {
            sink.record(Instant.fromEpochMilliseconds(it.toLong()), DiagnosticKind.StopRequested)
        }
        val files = requireNotNull(dir.listFiles())
        assertEquals(setOf("events.log", "previous.log"), files.map { it.name }.toSet())
        assertTrue(
            files.all {
                it.length() <= 80 &&
                    it.readLines().all { line -> line.matches(Regex("[0-9]+ StopRequested")) }
            }
        )
        assertEquals("29 StopRequested", File(dir, "events.log").readLines().last())
    }

    @org.junit.Test
    fun journalAndVaultSurviveReopenAndRejectStaleMutation() = runBlocking {
        val dir = File(context.cacheDir, "journal-vault").apply { mkdirs() }
        fun file(name: String) = AtomicBytes(File(dir, name)) {}
        val ref =
            DeviceRef(DeviceId("a"), "Synthetic", DeviceFamily.Handy, ConnectionKind.HandyCloud)
        val journal = FileJournal(file("run"))
        val r = RunJournal(RunId("first"), listOf(ref), RunIntent.Start, false, at)
        assertTrue(journal.armIfEmpty(r))
        assertFalse(journal.armIfEmpty(r.copy(runId = RunId("later"))))
        val next = FileJournal(file("run"))
        next.reconcile(RunId("other"), setOf(ref.id))
        assertEquals(r, next.read())
        assertTrue(next.transition(r.copy(intended = RunIntent.Paused)))
        next.reconcile(r.runId, setOf(ref.id))
        assertNull(next.read())
        file("run").replace("broken".encodeToByteArray())
        assertFailsWith<IOException> { next.armIfEmpty(r) }
        val generated = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val vault = AndroidVault({ file(it.value) }, { generated })
        val handle = SecretHandle("vault")
        assertTrue(vault.replace(handle, null, Secret("synthetic secret")))
        assertFalse(vault.replace(handle, null, Secret("old")))
        assertEquals(Secret("synthetic secret"), vault.get(handle))
        assertFalse(File(dir, "vault").readBytes().decodeToString().contains("synthetic"))
        val unreadableHandle = SecretHandle("damaged")
        file("damaged").replace(byteArrayOf(1, 2, 3))
        val repairing = vault.prepareSignIn(unreadableHandle)
        assertNull(repairing.original)
        assertTrue(repairing.commit(Secret("new synthetic registration")))
        assertEquals(Secret("new synthetic registration"), vault.get(unreadableHandle))
        val obsolete = vault.prepareSignIn(unreadableHandle)
        vault.remove(unreadableHandle)
        assertFalse(obsolete.commit(Secret("late registration")))
        val removed = vault.takeForRemoval(handle)
        assertNull(vault.get(handle))
        removed.restoreIfMissing()
        assertEquals(Secret("synthetic secret"), vault.get(handle))
        val earlier = vault.takeForRemoval(handle)
        vault.put(handle, Secret("newer synthetic secret"))
        earlier.restoreIfMissing()
        assertEquals(Secret("newer synthetic secret"), vault.get(handle))
        vault.remove(handle)
        assertNull(vault.get(handle))
    }

    @org.junit.Test
    fun olderHistoryLoadsFromTheCurrentCursor() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, PlatformDatabase::class.java).build()
        try {
            val stores = RoomStores(db, scope)
            val id = ChatId("synthetic-history")
            val at = Instant.fromEpochMilliseconds(0)
            stores.chats.create(ChatSummary(id, "Synthetic history", "Companion", at))
            repeat(520) { i ->
                val entry =
                    ChatEntry.Message(EntryId("entry-" + i), at, Author.You, "line " + i, null)
                check(stores.chats.append(id, entry, PersonaId(""), emptyList()) { true })
            }
            val exists =
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    db.records().entry("entry-0") != null
                }
            stores.chats.loadPage(id)
            val shown = stores.chats.state.value.chats.getValue(id).messages
            assertTrue(exists)
            assertEquals(513, shown.size)
            assertEquals("entry-7", shown.first().id.value)
            val opening = stores.chats.opening(id, 4).map { it.id.value }
            assertEquals((0..3).map { "entry-$it" }, opening)
            assertTrue(stores.chats.opening(ChatId("another-conversation"), 4).isEmpty())
            assertEquals(shown, stores.chats.state.value.chats.getValue(id).messages)
            assertTrue(stores.chats.state.value.chats.getValue(id).hasOlderMessages)
            stores.chats.loadOlder(id, shown.first().id)
            stores.chats.loadOlder(id, shown.first().id)
            val full = stores.chats.state.value.chats.getValue(id)
            assertEquals((0 until 520).map { "entry-" + it }, full.messages.map { it.id.value })
            assertFalse(full.hasOlderMessages)
            assertEquals(
                shown.recentModelEntries().toList(),
                full.messages.recentModelEntries().toList(),
            )
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @org.junit.Test
    fun legacyBlankTitleIsRestoredFromTheFirstUserMessage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, PlatformDatabase::class.java).build()
        try {
            val stores = RoomStores(db, scope)
            val id = ChatId("legacy")
            val summary = ChatSummary(id, "", "Companion", at)
            stores.chats.create(summary)
            for ((i, author) in listOf(Author.Persona, Author.You, Author.You).withIndex()) {
                val entry = ChatEntry.Message(EntryId("legacy-$i"), at, author, "Message $i", null)
                check(stores.chats.append(id, entry, PersonaId(""), emptyList()) { true })
            }
            withContext(Dispatchers.IO) {
                val old = requireNotNull(db.records().header(id.value))
                db.records().update(old.copy(payload = Json.encodeToString(summary)))
            }
            val restored = RoomStores(db, scope).also { it.awaitReady() }
            assertEquals("Message 1", restored.chats.state.value.chats.getValue(id).summary.title)
            withContext(Dispatchers.IO) {
                val header = requireNotNull(db.records().header(id.value))
                assertEquals("Message 1", Json.decodeFromString<ChatSummary>(header.payload).title)
            }
        } finally {
            scope.cancel()
            db.close()
        }
    }
}

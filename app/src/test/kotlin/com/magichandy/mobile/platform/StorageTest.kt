package com.magichandy.mobile.platform

import android.content.Context
import androidx.room.Room
import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.platform.storage.*
import java.io.File
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
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
        vault.remove(handle)
        assertNull(vault.get(handle))
    }
}

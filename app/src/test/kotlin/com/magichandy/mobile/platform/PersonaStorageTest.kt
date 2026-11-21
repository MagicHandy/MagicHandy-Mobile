package com.magichandy.mobile.platform

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.ChatSummary
import com.magichandy.mobile.core.api.EntryId
import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.ports.PersonaChange
import com.magichandy.mobile.core.ports.PreferenceField
import com.magichandy.mobile.core.ports.activePersona
import com.magichandy.mobile.core.ports.builtInPersona
import com.magichandy.mobile.platform.storage.Document
import com.magichandy.mobile.platform.storage.PlatformDatabase
import com.magichandy.mobile.platform.storage.RoomStores
import java.io.File
import java.io.IOException
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
internal class PersonaStorageTest {
    @Test
    fun personaDatabaseIsExcludedFromCloudBackupAndDeviceTransfer() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(
            0,
            app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
        val db = PlatformDatabase.open(app)
        try {
            assertEquals(
                app.noBackupFilesDir.canonicalFile,
                File(requireNotNull(db.openHelper.databaseName)).canonicalFile.parentFile,
            )
        } finally {
            db.close()
        }
        val excluded = mutableMapOf<String, MutableSet<String>>()
        app.resources.getXml(com.magichandy.mobile.R.xml.data_extraction_rules).use { xml ->
            var mode = ""
            while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
                when (xml.name) {
                    "cloud-backup",
                    "device-transfer" -> mode = xml.name
                    "include" -> error("Backup allowlist exposes app data")
                    "exclude" -> {
                        assertTrue(xml.getAttributeValue(null, "path") in listOf(null, "."))
                        excluded
                            .getOrPut(mode) { mutableSetOf() }
                            .add(xml.getAttributeValue(null, "domain"))
                    }
                }
            }
        }
        val domains =
            setOf(
                "device_root",
                "device_file",
                "device_database",
                "device_sharedpref",
                "root",
                "file",
                "database",
                "sharedpref",
                "external",
            )
        assertEquals(mapOf("cloud-backup" to domains, "device-transfer" to domains), excluded)
    }

    @Test
    fun migrateVersionTwoAndCommitPersonaSettingsMemoryAndMessagesAcrossReopen() =
        runBlocking(Dispatchers.IO) {
            val app = RuntimeEnvironment.getApplication()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val chat = ChatId("old-chat")
            val now = Instant.fromEpochSeconds(2)
            val old = ChatEntry.Message(EntryId("old"), now, Author.Persona, "Older", null)
            val custom = PersonaRef(PersonaId("custom"), "Kit", "", Personality(Explicitness.Warm))
            suspend fun open(check: suspend (PlatformDatabase, RoomStores) -> Unit) {
                val db = PlatformDatabase.open(app)
                try {
                    val stores = RoomStores(db, scope)
                    stores.awaitReady()
                    check(db, stores)
                } finally {
                    db.close()
                }
            }
            try {
                open { db, stores ->
                    stores.chats.create(ChatSummary(chat, "Synthetic", "MagicHandy", now))
                    assertTrue(
                        stores.chats.append(chat, old, builtInPersona.id, listOf("old memory")) {
                            true
                        }
                    )
                    db.records()
                        .put(
                            Document(
                                "settings",
                                """{"revision":4,"values":{"adultsOnly":true},"changedAt":{"adults_only":4}}""",
                            )
                        )
                }
                val file = File(app.noBackupFilesDir, "magichandy.db")
                SQLiteDatabase.openDatabase(file.path, null, 0).use {
                    it.execSQL(
                        """UPDATE messages SET payload = REPLACE(payload, ',"speaker":null', '')"""
                    )
                    it.rawQuery("SELECT payload FROM messages", null).use { row ->
                        assertTrue(row.moveToFirst())
                        assertFalse("speaker" in row.getString(0))
                    }
                    it.execSQL("DROP TABLE personas")
                    it.execSQL("DELETE FROM room_master_table")
                    it.version = 2
                }
                open { _, stores ->
                    assertTrue(stores.settings.state.value.values.isSetupDone)
                    assertEquals(4L, stores.settings.state.value.revision)
                    assertEquals(builtInPersona, stores.settings.state.value.activePersona)
                    assertNull(
                        (stores.chats.state.value.chats.getValue(chat).messages.single()
                                as ChatEntry.Message)
                            .speaker
                    )
                    assertEquals(
                        listOf("old memory"),
                        stores.chats.state.value.memory[builtInPersona.id],
                    )
                    assertEquals(
                        FormResult.Accepted,
                        stores.settings.editPersonas {
                            PersonaChange.Edit(
                                save = custom,
                                fields =
                                    listOf(
                                        PreferenceField.BuiltInPersonality.edit(
                                            Personality(description = "Built-in draft")
                                        ),
                                        PreferenceField.SelectedPersona.edit(custom.id),
                                    ),
                            )
                        },
                    )
                    assertTrue(
                        stores.chats.append(
                            chat,
                            old.copy(id = EntryId("new"), speaker = custom.name),
                            custom.id,
                            listOf("new memory"),
                        ) {
                            true
                        }
                    )
                }
                open { db, stores ->
                    assertEquals(custom, stores.settings.state.value.activePersona)
                    assertEquals(
                        "Built-in draft",
                        stores.settings.state.value.values.builtInPersonality.description,
                    )
                    val remove =
                        PersonaChange.Edit(
                            delete = custom.id,
                            fields =
                                listOf(PreferenceField.SelectedPersona.edit(builtInPersona.id)),
                        )
                    val sql = db.openHelper.writableDatabase
                    sql.execSQL(
                        "CREATE TRIGGER hold_memory BEFORE DELETE ON memories BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END"
                    )
                    assertFailsWith<IOException> { stores.settings.editPersonas { remove } }
                    assertEquals(custom, stores.settings.state.value.activePersona)
                    assertTrue(db.records().hasPersona(custom.id.value))
                    assertEquals(listOf("new memory"), stores.chats.state.value.memory[custom.id])
                    sql.execSQL("DROP TRIGGER hold_memory")
                    stores.settings.editPersonas { remove }
                    assertTrue(
                        stores.chats.append(
                            chat,
                            old.copy(id = EntryId("late"), speaker = custom.name),
                            custom.id,
                            listOf("late"),
                        ) {
                            true
                        }
                    )
                }
                open { _, stores ->
                    assertTrue(stores.settings.state.value.activePersona.isBuiltIn)
                    assertTrue(stores.settings.state.value.customPersonas.isEmpty())
                    assertFalse(custom.id in stores.chats.state.value.memory)
                    val transcript =
                        stores.chats.state.value.chats
                            .getValue(chat)
                            .messages
                            .filterIsInstance<ChatEntry.Message>()
                    assertEquals(listOf(null, "Kit", "Kit"), transcript.map { it.speaker })
                }
            } finally {
                scope.cancel()
            }
        }
}

package com.magichandy.mobile.platform.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "documents")
internal data class Document(@PrimaryKey val name: String, val payload: String)

@Entity(tableName = "chats")
internal data class Header(@PrimaryKey val id: String, val payload: String, val activity: Long)

@Entity(
    tableName = "messages",
    indices = [Index("entryId", unique = true), Index("chatId", "sequence")],
)
internal data class Entry(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val entryId: String,
    val chatId: String,
    val payload: String,
)

@Entity(tableName = "memories", primaryKeys = ["persona", "text"])
internal data class Memory(val persona: String, val text: String)

@Entity(tableName = "personas")
internal data class PersonaRow(
    @PrimaryKey val id: String,
    val name: String,
    val personality: String,
)

@Dao
internal interface RecordsDao {
    @Query("SELECT payload FROM documents WHERE name = :name") fun get(name: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: Document)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun create(row: Header): Long

    @Update fun update(row: Header)

    @Query("SELECT * FROM chats ORDER BY activity DESC") fun headers(): List<Header>

    @Query("SELECT * FROM chats WHERE id = :id") fun header(id: String): Header?

    @Query("SELECT * FROM messages WHERE entryId = :id") fun entry(id: String): Entry?

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun append(row: Entry): Long

    @Query("SELECT payload FROM messages WHERE chatId = :id ORDER BY sequence LIMIT :limit")
    fun opening(id: String, limit: Int): List<String>

    @Query("SELECT payload FROM messages WHERE chatId = :id ORDER BY sequence DESC LIMIT :limit")
    fun page(id: String, limit: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun remember(rows: List<Memory>)

    @Query(
        "SELECT payload FROM messages WHERE chatId = :id AND sequence < (SELECT sequence FROM messages WHERE chatId = :id AND entryId = :before) ORDER BY sequence DESC LIMIT :limit"
    )
    fun before(id: String, before: String, limit: Int): List<String>

    @Query(
        "SELECT * FROM messages WHERE chatId = :id AND sequence > :after ORDER BY sequence LIMIT 32"
    )
    fun nextForTitle(id: String, after: Long): List<Entry>

    @Query("SELECT text FROM memories WHERE persona = :persona ORDER BY rowid")
    fun memoryFor(persona: String): List<String>

    @Query("SELECT * FROM memories ORDER BY rowid") fun memories(): List<Memory>

    @Query("DELETE FROM memories WHERE persona = :persona") fun forget(persona: String)

    @Query("DELETE FROM messages") fun clearMessages()

    @Query("DELETE FROM chats") fun clearHeaders()

    @Query("DELETE FROM memories") fun clearMemories()

    @Query("SELECT * FROM personas") fun personas(): List<PersonaRow>

    @Query("SELECT EXISTS(SELECT 1 FROM personas WHERE id = :id)")
    fun hasPersona(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun savePersona(row: PersonaRow)

    @Query("DELETE FROM personas WHERE id = :id") fun deletePersona(id: String)
}

@Database(
    entities = [Document::class, Header::class, Entry::class, Memory::class, PersonaRow::class],
    version = 3,
    exportSchema = true,
)
internal abstract class PlatformDatabase : RoomDatabase() {
    abstract fun records(): RecordsDao

    companion object {
        val fromOne =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_messages_chatId_sequence ON messages(chatId, sequence)"
                    )
                }
            }

        val fromTwo =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS personas (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, personality TEXT NOT NULL)"
                    )
                }
            }

        fun open(context: Context, name: String = "magichandy.db"): PlatformDatabase =
            Room.databaseBuilder(
                    context,
                    PlatformDatabase::class.java,
                    java.io.File(context.noBackupFilesDir, name).absolutePath,
                )
                .addMigrations(fromOne, fromTwo)
                .build()
    }
}

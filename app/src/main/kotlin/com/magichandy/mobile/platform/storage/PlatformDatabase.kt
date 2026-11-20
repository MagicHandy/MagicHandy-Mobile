package com.magichandy.mobile.platform.storage

import android.content.Context
import androidx.room.*
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

    @Query("SELECT payload FROM messages WHERE chatId = :id ORDER BY sequence DESC LIMIT :limit")
    fun page(id: String, limit: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun remember(rows: List<Memory>)

    @Query("SELECT text FROM memories WHERE persona = :persona ORDER BY rowid")
    fun memoryFor(persona: String): List<String>

    @Query("SELECT * FROM memories ORDER BY rowid") fun memories(): List<Memory>

    @Query("DELETE FROM memories WHERE persona = :persona") fun forget(persona: String)

    @Query("DELETE FROM messages") fun clearMessages()

    @Query("DELETE FROM chats") fun clearHeaders()

    @Query("DELETE FROM memories") fun clearMemories()
}

@Database(
    entities = [Document::class, Header::class, Entry::class, Memory::class],
    version = 2,
    exportSchema = false,
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

        fun open(context: Context): PlatformDatabase =
            Room.databaseBuilder(
                    context,
                    PlatformDatabase::class.java,
                    java.io.File(context.noBackupFilesDir, "magichandy.db").absolutePath,
                )
                .addMigrations(fromOne)
                .build()
    }
}

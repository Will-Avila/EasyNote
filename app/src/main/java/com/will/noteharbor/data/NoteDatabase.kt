package com.will.noteharbor.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val type: String,
    val color: String,
    val pinned: Boolean,
    val archived: Boolean,
    val trashed: Boolean,
    val trashedAt: Long?,
    val locked: Boolean,
    val passwordHash: String,
    val encryptedContent: String,
    val attachments: String,
    val updatedAt: Long,
    val updatedBy: String,
    val reminderRecurrence: String?,
    val reminderHour: Int?,
    val reminderMinute: Int?,
    val reminderDayOfWeek: Int?,
    val reminderDaysOfWeek: String?,
    val reminderDayOfMonth: Int?,
    val reminderDate: String?,
)

@Entity(
    tableName = "checklist_items",
    primaryKeys = ["noteId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("noteId")],
)
data class ChecklistItemEntity(
    val noteId: String,
    val position: Int,
    val text: String,
    val completed: Boolean,
)

@Entity(tableName = "note_tombstones")
data class TombstoneEntity(
    @PrimaryKey val noteId: String,
    val deletedAt: Long,
)

@Entity(tableName = "database_metadata")
data class DatabaseMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes")
    fun loadNotes(): List<NoteEntity>

    @Query("SELECT * FROM checklist_items ORDER BY noteId, position")
    fun loadChecklistItems(): List<ChecklistItemEntity>

    @Query("SELECT * FROM note_tombstones")
    fun loadTombstones(): List<TombstoneEntity>

    @Query("SELECT * FROM database_metadata WHERE `key` = :key LIMIT 1")
    fun loadMetadata(key: String): DatabaseMetadataEntity?

    @Query("SELECT COUNT(*) FROM notes")
    fun countNotes(): Int

    @Query("DELETE FROM checklist_items")
    fun deleteAllChecklistItems()

    @Query("DELETE FROM notes")
    fun deleteAllNotes()

    @Query("DELETE FROM note_tombstones")
    fun deleteAllTombstones()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertNotes(notes: List<NoteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertChecklistItems(items: List<ChecklistItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTombstones(tombstones: List<TombstoneEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMetadata(metadata: DatabaseMetadataEntity)
}

@Database(
    entities = [NoteEntity::class, ChecklistItemEntity::class, TombstoneEntity::class, DatabaseMetadataEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE notes ADD COLUMN reminderRecurrence TEXT")
        database.execSQL("ALTER TABLE notes ADD COLUMN reminderHour INTEGER")
        database.execSQL("ALTER TABLE notes ADD COLUMN reminderMinute INTEGER")
        database.execSQL("ALTER TABLE notes ADD COLUMN reminderDayOfWeek INTEGER")
        database.execSQL("ALTER TABLE notes ADD COLUMN reminderDayOfMonth INTEGER")
    }
}

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE notes ADD COLUMN reminderDaysOfWeek TEXT")
        database.execSQL("ALTER TABLE notes ADD COLUMN reminderDate TEXT")
    }
}

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE notes ADD COLUMN trashed INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE notes ADD COLUMN encryptedContent TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE notes ADD COLUMN trashedAt INTEGER")
        // Notas já na lixeira antes do upgrade: usa a última modificação como marco, para que
        // também expirem normalmente após o período de retenção.
        database.execSQL("UPDATE notes SET trashedAt = updatedAt WHERE trashed = 1 AND trashedAt IS NULL")
    }
}

val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE notes ADD COLUMN attachments TEXT NOT NULL DEFAULT ''")
    }
}
object EncryptedDatabaseFactory {
    private const val SQLCIPHER_LIBRARY = "sqlcipher"

    fun open(context: Context, databasePath: String, passphrase: ByteArray): NoteDatabase {
        runCatching { System.loadLibrary(SQLCIPHER_LIBRARY) }
            .getOrElse { error -> throw IllegalStateException("Não foi possível carregar a criptografia do banco", error) }
        val factory = SupportOpenHelperFactory(passphrase)
        return Room.databaseBuilder(context.applicationContext, NoteDatabase::class.java, databasePath)
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
    }
}

object PlainDatabaseFactory {
    fun open(context: Context, databasePath: String): NoteDatabase =
        Room.databaseBuilder(context.applicationContext, NoteDatabase::class.java, databasePath)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
}

internal fun NoteStoreSnapshot.toEntities(): Triple<List<NoteEntity>, List<ChecklistItemEntity>, List<TombstoneEntity>> {
    val noteEntities = notes.map { note ->
        NoteEntity(
            id = note.id,
            title = note.title,
            body = note.body,
            type = note.type.name,
            color = note.color.name,
            pinned = note.pinned,
            archived = note.archived,
            trashed = note.trashed,
            trashedAt = note.trashedAt,
            locked = note.locked,
            passwordHash = note.passwordHash,
            encryptedContent = note.encryptedContent,
            attachments = AttachmentListCodec.encode(note.attachments),
            updatedAt = note.updatedAt,
            updatedBy = note.updatedBy,
            reminderRecurrence = note.reminder?.recurrence?.name,
            reminderHour = note.reminder?.hour,
            reminderMinute = note.reminder?.minute,
            reminderDayOfWeek = note.reminder?.daysOfWeek?.minOrNull(),
            reminderDaysOfWeek = note.reminder?.daysOfWeek?.sorted()?.joinToString(",")?.takeIf { it.isNotBlank() },
            reminderDayOfMonth = note.reminder?.dayOfMonth,
            reminderDate = note.reminder?.date?.toString(),
        )
    }
    val itemEntities = notes.flatMap { note ->
        note.items.mapIndexed { position, item ->
            ChecklistItemEntity(note.id, position, item.text, item.completed)
        }
    }
    val tombstoneEntities = tombstones.map { (noteId, deletedAt) -> TombstoneEntity(noteId, deletedAt) }
    return Triple(noteEntities, itemEntities, tombstoneEntities)
}

internal fun List<NoteEntity>.toNotes(items: List<ChecklistItemEntity>): List<Note> {
    val itemsByNote = items.groupBy { it.noteId }
    return map { entity ->
        val reminder = if (entity.reminderRecurrence != null && entity.reminderHour != null && entity.reminderMinute != null) {
            runCatching {
                ReminderSchedule(
                    recurrence = ReminderRecurrence.valueOf(entity.reminderRecurrence),
                    hour = entity.reminderHour,
                    minute = entity.reminderMinute,
                    daysOfWeek = entity.reminderDaysOfWeek
                        ?.split(",")
                        ?.mapNotNull(String::toIntOrNull)
                        ?.toSet()
                        ?.ifEmpty { entity.reminderDayOfWeek?.let(::setOf).orEmpty() }
                        ?: entity.reminderDayOfWeek?.let(::setOf).orEmpty(),
                    dayOfMonth = entity.reminderDayOfMonth,
                    date = entity.reminderDate?.let { java.time.LocalDate.parse(it) },
                )
            }.getOrNull()
        } else {
            null
        }
        Note(
            id = entity.id,
            title = entity.title,
            body = entity.body,
            type = runCatching { NoteType.valueOf(entity.type) }.getOrDefault(NoteType.TEXT),
            color = runCatching { NoteColor.valueOf(entity.color) }.getOrDefault(NoteColor.SUN),
            pinned = entity.pinned,
            archived = entity.archived,
            trashed = entity.trashed,
            trashedAt = entity.trashedAt,
            locked = entity.locked,
            passwordHash = entity.passwordHash,
            encryptedContent = entity.encryptedContent,
            attachments = AttachmentListCodec.decode(entity.attachments),
            updatedAt = entity.updatedAt,
            updatedBy = entity.updatedBy,
            items = itemsByNote[entity.id].orEmpty()
                .sortedBy { it.position }
                .map { ChecklistItem(it.text, it.completed) },
            reminder = reminder,
        )
    }
}

internal fun NoteDao.loadSnapshot(): NoteStoreSnapshot = NoteStoreSnapshot(
    notes = loadNotes().toNotes(loadChecklistItems()),
    tombstones = loadTombstones().associate { it.noteId to it.deletedAt },
)

internal fun NoteDatabase.replaceSnapshot(snapshot: NoteStoreSnapshot) {
    val (notes, items, tombstones) = snapshot.toEntities()
    runInTransaction {
        noteDao().deleteAllChecklistItems()
        noteDao().deleteAllNotes()
        noteDao().deleteAllTombstones()
        if (notes.isNotEmpty()) noteDao().insertNotes(notes)
        if (items.isNotEmpty()) noteDao().insertChecklistItems(items)
        if (tombstones.isNotEmpty()) noteDao().insertTombstones(tombstones)
    }
}

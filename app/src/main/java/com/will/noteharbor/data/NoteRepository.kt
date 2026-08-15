package com.will.noteharbor.data

import android.content.Context
import java.security.SecureRandom
import java.util.UUID

class NoteRepository(context: Context) {
    private val appContext = context.applicationContext
    private val secureSecrets = SecureSecretStore(appContext)
    private val legacyPreferences = appContext.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val databasePassphrase: ByteArray
    private val database: NoteDatabase

    val deviceId: String

    init {
        deviceId = secureSecrets.getString(DEVICE_ID_KEY) ?: UUID.randomUUID().toString().also {
            secureSecrets.putString(DEVICE_ID_KEY, it)
        }
        databasePassphrase = secureSecrets.getBytes(LOCAL_DATABASE_KEY) ?: ByteArray(DATABASE_KEY_BYTES)
            .also(SecureRandom()::nextBytes)
            .also { secureSecrets.putBytes(LOCAL_DATABASE_KEY, it) }
        database = EncryptedDatabaseFactory.open(
            context = appContext,
            databasePath = appContext.getDatabasePath(DATABASE_NAME).absolutePath,
            passphrase = databasePassphrase,
        )
        migrateLegacyDataIfNeeded()
    }

    fun close() {
        database.close()
        databasePassphrase.fill(0)
    }

    fun load(): List<Note> = synchronized(this) {
        database.noteDao().loadSnapshot().notes
    }

    fun loadSnapshot(): NoteStoreSnapshot = synchronized(this) {
        database.noteDao().loadSnapshot()
    }

    fun save(notes: List<Note>) = synchronized(this) {
        val current = database.noteDao().loadSnapshot()
        val now = System.currentTimeMillis()
        val currentIds = current.notes.map { it.id }.toSet()
        val newIds = notes.map { it.id }.toSet()
        val tombstones = current.tombstones.toMutableMap().apply {
            (currentIds - newIds).forEach { id ->
                val previousUpdatedAt = current.notes.firstOrNull { it.id == id }?.updatedAt ?: Long.MIN_VALUE
                this[id] = maxOf(this[id] ?: Long.MIN_VALUE, now, previousUpdatedAt + 1)
            }
            notes.forEach { note ->
                if (note.updatedAt > (this[note.id] ?: Long.MIN_VALUE)) remove(note.id)
            }
        }
        database.replaceSnapshot(NoteStoreSnapshot(notes, tombstones))
    }

    fun replaceSnapshot(snapshot: NoteStoreSnapshot) = synchronized(this) {
        database.replaceSnapshot(snapshot)
    }

    fun mergeRemoteSnapshot(remote: NoteStoreSnapshot): NoteStoreSnapshot = synchronized(this) {
        val merged = SyncMerger.merge(database.noteDao().loadSnapshot(), remote)
        database.replaceSnapshot(merged)
        merged
    }

    fun clearReminder(noteId: String) = synchronized(this) {
        val snapshot = database.noteDao().loadSnapshot()
        if (snapshot.notes.none { it.id == noteId && it.reminder != null }) return@synchronized
        save(snapshot.notes.map { note ->
            if (note.id == noteId) {
                note.copy(
                    reminder = null,
                    updatedAt = System.currentTimeMillis(),
                    updatedBy = deviceId,
                )
            } else {
                note
            }
        })
    }

    private fun migrateLegacyDataIfNeeded() {
        synchronized(this) {
            val dao = database.noteDao()
            if (dao.loadMetadata(MIGRATION_KEY) != null) return
            val legacy = legacyPreferences.getString(LEGACY_NOTES_KEY, null)?.takeIf { it.isNotBlank() }
            val decodedLegacy = legacy?.let { runCatching { NoteJsonCodec.decode(it) }.getOrNull() }
            if (dao.countNotes() > 0) {
                dao.insertMetadata(DatabaseMetadataEntity(MIGRATION_KEY, MIGRATION_VERSION))
                legacyPreferences.edit().remove(LEGACY_NOTES_KEY).apply()
                return
            }
            if (legacy != null && decodedLegacy == null) {
                database.replaceSnapshot(NoteStoreSnapshot(emptyList(), emptyMap()))
                dao.insertMetadata(DatabaseMetadataEntity(MIGRATION_KEY, MIGRATION_VERSION))
                legacyPreferences.edit().remove(LEGACY_NOTES_KEY).apply()
                return
            }
            val notes = NoteMigrationPolicy.notesForInitialStore(decodedLegacy)
                .map { if (it.updatedBy == "local") it.copy(updatedBy = deviceId) else it }
            database.replaceSnapshot(NoteStoreSnapshot(notes = notes, tombstones = emptyMap()))
            dao.insertMetadata(DatabaseMetadataEntity(MIGRATION_KEY, MIGRATION_VERSION))
            legacyPreferences.edit().remove(LEGACY_NOTES_KEY).apply()
        }
    }

    private companion object {
        const val DATABASE_NAME = "notas.db"
        const val DATABASE_KEY_BYTES = 32
        const val DEVICE_ID_KEY = "device-id"
        const val LOCAL_DATABASE_KEY = "local-database-key"
        const val LEGACY_PREFERENCES_NAME = "noteharbor.preferences"
        const val LEGACY_NOTES_KEY = "notes"
        const val MIGRATION_KEY = "legacy-json-migration"
        const val MIGRATION_VERSION = "1"
    }
}

package com.will.noteharbor.data

import android.content.Context
import com.google.api.services.drive.Drive
import java.io.File

data class CloudBackupSettings(
    val automatic: Boolean = true,
    val lastSyncAt: Long? = null,
)

class CloudBackupSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): CloudBackupSettings = CloudBackupSettings(
        automatic = preferences.getBoolean(AUTOMATIC_KEY, true),
        lastSyncAt = preferences.getLong(LAST_SYNC_KEY, 0L).takeIf { it > 0L },
    )

    fun setAutomatic(enabled: Boolean) {
        preferences.edit().putBoolean(AUTOMATIC_KEY, enabled).apply()
    }

    fun markSynced(at: Long = System.currentTimeMillis()) {
        preferences.edit().putLong(LAST_SYNC_KEY, at).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "noteharbor.cloud-backup"
        const val AUTOMATIC_KEY = "automatic"
        const val LAST_SYNC_KEY = "last-sync-at"
    }
}

data class CloudSyncResult(
    val noteCount: Int,
)

enum class CloudSyncPhase {
    DISCONNECTED,
    SYNCING,
    SYNCED,
    ERROR,
}

data class CloudSyncState(
    val phase: CloudSyncPhase,
    val noteCount: Int? = null,
    val message: String? = null,
)

class CloudBackupSynchronizer(
    private val context: Context,
    private val localRepository: NoteRepository,
) {
    fun synchronize(drive: Drive): CloudSyncResult {
        val existingBytes = DriveBackupStorage.readExisting(drive)
        val remote = existingBytes?.let(::readPlainSnapshot)
        val merged = if (remote != null) {
            localRepository.mergeRemoteSnapshot(remote)
        } else {
            localRepository.loadSnapshot()
        }
        val bytes = createPlainSnapshot(merged)
        DriveBackupStorage.write(drive, bytes)
        return CloudSyncResult(merged.notes.size)
    }

    private fun readPlainSnapshot(bytes: ByteArray): NoteStoreSnapshot {
        val databaseFile = temporaryDatabaseFile()
        return try {
            databaseFile.writeBytes(bytes)
            val database = PlainDatabaseFactory.open(context, databaseFile.absolutePath)
            try {
                database.noteDao().loadSnapshot()
            } finally {
                database.close()
            }
        } finally {
            deleteTemporaryDatabase(databaseFile)
        }
    }

    private fun createPlainSnapshot(snapshot: NoteStoreSnapshot): ByteArray {
        val databaseFile = temporaryDatabaseFile()
        return try {
            databaseFile.delete()
            val database = PlainDatabaseFactory.open(context, databaseFile.absolutePath)
            try {
                database.replaceSnapshot(snapshot)
            } finally {
                database.close()
            }
            databaseFile.readBytes()
        } finally {
            deleteTemporaryDatabase(databaseFile)
        }
    }

    private fun temporaryDatabaseFile(): File = File.createTempFile("notas-cloud-", ".db", context.cacheDir)

    private fun deleteTemporaryDatabase(file: File) {
        file.delete()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        File(file.path + "-journal").delete()
    }
}

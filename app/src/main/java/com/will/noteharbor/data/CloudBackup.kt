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
    /** True quando o backup remoto traz o pacote de segurança e este aparelho não tem nenhuma
     *  configuração de segurança (pós-limpeza de dados/instalação nova). */
    val securityRestorePending: Boolean = false,
    /** Envelope cifrado do estado de segurança, lido do backup remoto. */
    val securityPayload: String? = null,
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

/** Snapshot lido do backup na nuvem, junto do envelope de segurança embutido (se houver). */
private data class CloudSnapshotPayload(
    val snapshot: NoteStoreSnapshot,
    val securityState: String?,
)

class CloudBackupSynchronizer(
    private val context: Context,
    private val localRepository: NoteRepository,
) {
    fun synchronize(drive: Drive): CloudSyncResult {
        val existingBytes = DriveBackupStorage.readExisting(drive)
        val remote = existingBytes?.let(::readPlainSnapshot)
        val merged = if (remote != null) {
            localRepository.mergeRemoteSnapshot(remote.snapshot)
        } else {
            localRepository.loadSnapshot()
        }
        // Só restaura a segurança do backup quando este aparelho está sem NENHUMA configuração
        // (pós-limpeza de dados/instalação nova). Local configurado sempre vence.
        val restorePending = remote?.securityState != null && SecurityRecovery.isFresh(context)
        val bytes = createPlainSnapshot(merged, remote?.securityState)
        DriveBackupStorage.write(drive, bytes)
        return CloudSyncResult(
            noteCount = merged.notes.size,
            securityRestorePending = restorePending,
            securityPayload = remote?.securityState,
        )
    }

    private fun readPlainSnapshot(bytes: ByteArray): CloudSnapshotPayload {
        val databaseFile = temporaryDatabaseFile()
        return try {
            databaseFile.writeBytes(bytes)
            val database = PlainDatabaseFactory.open(context, databaseFile.absolutePath)
            try {
                val snapshot = database.noteDao().loadSnapshot()
                restoreAttachmentBytes(database)
                val securityState = database.noteDao().loadMetadata(SecurityRecovery.METADATA_KEY)?.value
                CloudSnapshotPayload(snapshot, securityState)
            } finally {
                database.close()
            }
        } finally {
            deleteTemporaryDatabase(databaseFile)
        }
    }

    /** Restaura os arquivos de anexo embutidos no backup (tabela `attachment_data`), byte a byte. */
    private fun restoreAttachmentBytes(database: NoteDatabase) {
        val db = database.openHelper.writableDatabase
        val hasTable = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'attachment_data'",
        )?.use { it.count > 0 } ?: false
        if (!hasTable) return
        db.query("SELECT id, data FROM attachment_data ORDER BY noteId, position")?.use { cursor ->
            while (cursor.moveToNext()) {
                val attachmentId = cursor.getString(0) ?: continue
                val blob = cursor.getBlob(1)
                if (attachmentId.isNotBlank() && blob != null) {
                    AttachmentStore.storeBytes(context, attachmentId, blob)
                }
            }
        }
    }

    private fun createPlainSnapshot(snapshot: NoteStoreSnapshot, fallbackSecurity: String?): ByteArray {
        val databaseFile = temporaryDatabaseFile()
        return try {
            databaseFile.delete()
            val database = PlainDatabaseFactory.open(context, databaseFile.absolutePath)
            try {
                database.replaceSnapshot(snapshot)
                writeAttachmentBytes(database, snapshot)
                // Estado de segurança cifrado. Quando não dá para re-cifrar agora (pós-limpeza,
                // sem a senha de recuperação ainda digitada), preserva o envelope antigo do
                // backup remoto — senão esta sync apagaria o pacote do Drive.
                // CUIDADO: `a ?: b?.let{...}` parseia como `a ?: (b?.let{...})` — o let só rodaria
                // quando `a` fosse null. Extrair para um val garante gravar SEMPRE que houver
                // envelope (o caso normal, com senha de recuperação definida, chegava aqui e
                // nunca gravava o pacote no backup).
                val securityEnvelope = SecurityRecovery.encryptCurrent(context) ?: fallbackSecurity
                securityEnvelope?.let {
                    database.noteDao().insertMetadata(DatabaseMetadataEntity(SecurityRecovery.METADATA_KEY, it))
                }
            } finally {
                database.close()
            }
            databaseFile.readBytes()
        } finally {
            deleteTemporaryDatabase(databaseFile)
        }
    }

    /**
     * Embuta os bytes dos anexos no arquivo de backup numa tabela SQL crua `attachment_data`.
     * O blob é o byte a byte do arquivo local — já cifrado quando a nota é protegida (opaco;
     * o backup inteiro é criptografado por PBKDF2 + SQLCipher).
     */
    private fun writeAttachmentBytes(database: NoteDatabase, snapshot: NoteStoreSnapshot) {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS attachment_data(" +
                "noteId TEXT NOT NULL, position INTEGER NOT NULL, id TEXT NOT NULL, " +
                "data BLOB NOT NULL, PRIMARY KEY(noteId, position))",
        )
        db.execSQL("DELETE FROM attachment_data")
        val insert = db.compileStatement(
            "INSERT OR REPLACE INTO attachment_data(noteId, position, id, data) VALUES(?, ?, ?, ?)",
        )
        try {
            snapshot.notes.forEach { note ->
                note.attachments.forEachIndexed { position, attachment ->
                    val bytes = AttachmentStore.readBytes(context, attachment.id) ?: return@forEachIndexed
                    insert.clearBindings()
                    insert.bindString(1, note.id)
                    insert.bindLong(2, position.toLong())
                    insert.bindString(3, attachment.id)
                    insert.bindBlob(4, bytes)
                    insert.executeInsert()
                }
            }
        } finally {
            insert.close()
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

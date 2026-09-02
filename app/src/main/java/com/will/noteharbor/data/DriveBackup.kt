package com.will.noteharbor.data

import android.accounts.Account
import android.content.Context
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import java.io.ByteArrayOutputStream

object DriveServiceFactory {
    fun create(context: Context, email: String): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            setOf(DriveScopes.DRIVE_APPDATA),
        )
        require(email.isNotBlank()) { "Conta Google sem e-mail associado." }
        credential.selectedAccount = Account(email, "com.google")
        return Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            credential,
        )
            .setApplicationName("noteharbor")
            .build()
    }
}

object DriveBackupStorage {
    private const val BACKUP_FILE_NAME = "notas-backup.db"
    // 512 MiB: os bytes dos anexos entram no backup, não só os metadados.
    private const val MAX_DATABASE_BYTES = 512 * 1024 * 1024

    fun readExisting(drive: Drive): ByteArray? {
        val fileId = drive.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = '$BACKUP_FILE_NAME'")
            .setFields("files(id)")
            .execute()
            .files
            ?.firstOrNull()
            ?.id ?: return null
        val output = ByteArrayOutputStream()
        drive.files().get(fileId).executeMediaAndDownloadTo(output)
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_DATABASE_BYTES) { "Arquivo de backup muito grande" }
        return bytes
    }

    fun write(drive: Drive, bytes: ByteArray) {
        val existing = drive.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = '$BACKUP_FILE_NAME'")
            .setFields("files(id)")
            .execute()
            .files
            .orEmpty()
        val media = ByteArrayContent("application/octet-stream", bytes)
        val primaryFile = existing.firstOrNull()
        if (primaryFile != null) {
            drive.files().update(primaryFile.id, File(), media).execute()
            existing.drop(1).forEach { duplicate ->
                runCatching { drive.files().delete(duplicate.id).execute() }
            }
        } else {
            val metadata = File().apply {
                name = BACKUP_FILE_NAME
                parents = listOf("appDataFolder")
            }
            val created = drive.files().create(metadata, media).execute()
            existing.filter { it.id != created.id }.forEach { stale ->
                runCatching { drive.files().delete(stale.id).execute() }
            }
        }
    }
}

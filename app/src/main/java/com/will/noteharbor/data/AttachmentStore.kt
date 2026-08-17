package com.will.noteharbor.data

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Lançada quando um anexo ultrapassa o limite de [AttachmentStore.MAX_BYTES]. */
class AttachmentTooLargeException(message: String) : Exception(message)

/**
 * Armazenamento dos arquivos anexados às notas, em diretório app-privado (sem permissões de storage).
 *
 * Um arquivo por anexo, nomeado pelo `attachmentId` (UUID; o nome de exibição fica só nos metadados
 * da nota). Se a nota é **protegida**, os bytes são cifrados com AES-256-GCM (chave derivada do
 * segredo da nota via [NoteEncryption.attachmentKey]) no formato
 * `"NOTAATT1" + IV(12) + ciphertext`; um arquivo sem o magic é texto puro. O estado é reconciliado
 * por [encryptFile]/[decryptFile] quando a proteção da nota muda.
 */
object AttachmentStore {
    const val MAX_BYTES = 20L * 1024 * 1024 // 20 MB por anexo

    private const val MAGIC = "NOTAATT1"
    private const val MAGIC_BYTES = 8
    private const val BUFFER_SIZE = 64 * 1024

    fun file(context: Context, attachmentId: String): File =
        File(context.filesDir, "attachments/$attachmentId")

    private fun cacheFile(context: Context, attachmentId: String): File =
        File(context.cacheDir, "attachment_cache/$attachmentId")

    /**
     * Copia os bytes de [uri] para o store e retorna o tamanho (bytes de texto puro). Se [secret]
     * não for null, o arquivo é gravado cifrado. Lança [AttachmentTooLargeException] acima do limite.
     */
    fun store(context: Context, attachmentId: String, uri: Uri, secret: String?): Long {
        val resolver = context.applicationContext.contentResolver
        val bytes = resolver.openInputStream(uri)?.use(::readCapped)
            ?: throw IllegalArgumentException("Não foi possível abrir o arquivo")
        write(context, attachmentId, bytes, secret)
        return bytes.size.toLong()
    }

    /** Grava [bytes] byte a byte no store (usado ao restaurar do backup: o blob já vem no formato final). */
    fun storeBytes(context: Context, attachmentId: String, bytes: ByteArray): Long {
        write(context, attachmentId, bytes, secret = null)
        return bytes.size.toLong()
    }

    private fun write(context: Context, attachmentId: String, bytes: ByteArray, secret: String?) {
        val data = if (secret == null) {
            bytes
        } else {
            val key = NoteEncryption.attachmentKey(secret)
            try {
                MAGIC.toByteArray(Charsets.US_ASCII) + NoteEncryption.encryptBytes(bytes, key)
            } finally {
                key.fill(0)
            }
        }
        val target = file(context, attachmentId)
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { it.write(data) }
    }

    fun exists(context: Context, attachmentId: String): Boolean = file(context, attachmentId).isFile

    /** Verdadeiro quando o arquivo do anexo está cifrado (tem o magic). */
    fun isEncrypted(context: Context, attachmentId: String): Boolean {
        val f = file(context, attachmentId)
        if (!f.isFile) return false
        val head = ByteArray(MAGIC_BYTES)
        FileInputStream(f).use { ins ->
            var read = 0
            while (read < MAGIC_BYTES) {
                val n = ins.read(head, read, MAGIC_BYTES - read)
                if (n < 0) break
                read += n
            }
        }
        return head.contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))
    }

    /** Lê os bytes crus do arquivo (cifrados ou não); null se não existir. */
    fun readBytes(context: Context, attachmentId: String): ByteArray? {
        val f = file(context, attachmentId)
        return f.takeIf { it.isFile }?.readBytes()
    }

    /**
     * Devolve um arquivo legível: se cifrado, decifra para o cache e retorna a cópia; senão devolve
     * o original. [secret] é obrigatório quando o arquivo está cifrado (senão retorna null).
     */
    fun openForRead(context: Context, attachmentId: String, secret: String?): File? {
        val f = file(context, attachmentId)
        if (!f.isFile) return null
        if (!isEncrypted(context, attachmentId)) return f
        val key = secret?.let { NoteEncryption.attachmentKey(it) } ?: return null
        val plain = try {
            val ciphertext = f.readBytes().copyOfRange(MAGIC_BYTES, f.length().toInt())
            NoteEncryption.decryptBytes(ciphertext, key)
        } finally {
            key.fill(0)
        }
        val cache = cacheFile(context, attachmentId)
        cache.parentFile?.mkdirs()
        FileOutputStream(cache).use { it.write(plain) }
        return cache
    }

    /** Re-cifra um anexo em texto puro. Não faz nada se já estiver cifrado ou sem [secret]. */
    fun encryptFile(context: Context, attachmentId: String, secret: String?) {
        if (secret == null || isEncrypted(context, attachmentId)) return
        val f = file(context, attachmentId)
        if (!f.isFile) return
        val key = NoteEncryption.attachmentKey(secret)
        val encrypted = try {
            MAGIC.toByteArray(Charsets.US_ASCII) + NoteEncryption.encryptBytes(f.readBytes(), key)
        } finally {
            key.fill(0)
        }
        FileOutputStream(f).use { it.write(encrypted) }
    }

    /** Decifra um anexo cifrado de volta a texto puro. Não faz nada se já estiver em texto puro. */
    fun decryptFile(context: Context, attachmentId: String, secret: String?) {
        if (secret == null || !isEncrypted(context, attachmentId)) return
        val f = file(context, attachmentId)
        val key = NoteEncryption.attachmentKey(secret)
        val plain = try {
            val ciphertext = f.readBytes().copyOfRange(MAGIC_BYTES, f.length().toInt())
            NoteEncryption.decryptBytes(ciphertext, key)
        } finally {
            key.fill(0)
        }
        FileOutputStream(f).use { it.write(plain) }
    }

    /** Apaga o arquivo e qualquer cópia decifrada no cache. */
    fun delete(context: Context, attachmentId: String) {
        file(context, attachmentId).delete()
        cacheFile(context, attachmentId).delete()
    }

    /**
     * Apaga todas as cópias decifradas do cache (chamado ao bloquear o app / voltar para o início,
     * quando nenhuma nota protegida está em tela). O cache é transitório: [openForRead] recria a
     * cópia ao reabrir o anexo.
     */
    fun clearCacheDir(context: Context) {
        File(context.cacheDir, "attachment_cache").deleteRecursively()
    }

    /** Apaga todos os anexos (reset de fábrica). Diretório inexistente é ignorado sem lançar. */
    fun deleteAll(context: Context) {
        File(context.filesDir, "attachments").deleteRecursively()
        File(context.cacheDir, "attachment_cache").deleteRecursively()
    }

    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) {
                throw AttachmentTooLargeException("Arquivo maior que ${MAX_BYTES / (1024 * 1024)} MB")
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

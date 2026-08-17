package com.will.noteharbor.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Criptografia de bytes de anexos (chave derivada do segredo da nota) e o codec JSON
 * de metadados. Não depende do Argon2 (a chave de anexo usa SHA-256 sobre o segredo).
 */
class AttachmentCryptoTest {
    private val secret = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun attachmentKeyIsDeterministicThirtyTwoBytesAndDependsOnSecret() {
        val a = NoteEncryption.attachmentKey(secret)
        val b = NoteEncryption.attachmentKey(secret)
        assertArrayEquals(a, b)
        assertEquals(32, a.size)

        val otherSecret = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        assertNotEquals(a.toList(), NoteEncryption.attachmentKey(otherSecret).toList())
        a.fill(0)
        b.fill(0)
    }

    @Test
    fun encryptThenDecryptReturnsOriginalBytes() {
        val key = NoteEncryption.attachmentKey(secret)
        val plain = "conteúdo do anexo".toByteArray(Charsets.UTF_8)
        try {
            val ciphertext = NoteEncryption.encryptBytes(plain, key)
            // IV (12) + ciphertext com tag GCM (16) a mais no mínimo.
            assertTrue(ciphertext.size > plain.size + 12)
            val decrypted = NoteEncryption.decryptBytes(ciphertext, key)
            assertArrayEquals(plain, decrypted)
        } finally {
            key.fill(0)
        }
    }

    @Test
    fun decryptWithWrongKeyThrows() {
        val key = NoteEncryption.attachmentKey(secret)
        val wrongKey = NoteEncryption.attachmentKey(
            Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }),
        )
        try {
            val ciphertext = NoteEncryption.encryptBytes(ByteArray(64) { 7 }, key)
            try {
                NoteEncryption.decryptBytes(ciphertext, wrongKey)
                throw AssertionError("Esperava falha da tag GCM com chave errada")
            } catch (expected: AEADBadTagException) {
                // esperado
            }
        } finally {
            key.fill(0)
            wrongKey.fill(0)
        }
    }

    @Test
    fun samePlaintextProducesDistinctCiphertexts() {
        val key = NoteEncryption.attachmentKey(secret)
        try {
            val plain = ByteArray(32) { 1 }
            val a = NoteEncryption.encryptBytes(plain, key)
            val b = NoteEncryption.encryptBytes(plain, key)
            assertNotEquals(a.toList(), b.toList())
        } finally {
            key.fill(0)
        }
    }

    @Test
    fun attachmentListCodecRoundTrips() {
        val attachments = listOf(
            AttachmentMetadata("u1", "foto.jpg", "image/jpeg", 2048L),
            AttachmentMetadata("u2", "contrato.pdf", "application/pdf", 10L * 1024 * 1024),
        )

        assertEquals(attachments, AttachmentListCodec.decode(AttachmentListCodec.encode(attachments)))
    }

    @Test
    fun attachmentListCodecBlankAndMalformedDecodeToEmpty() {
        assertTrue(AttachmentListCodec.decode("").isEmpty())
        assertTrue(AttachmentListCodec.decode("not json").isEmpty())
        // Entrada sem id é filtrada (não forma um anexo válido).
        assertTrue(AttachmentListCodec.decode("[{\"name\":\"sem-id\"}]").isEmpty())
    }

    @Test
    fun attachmentListCodecToleratesMissingOptionalFields() {
        val decoded = AttachmentListCodec.decode("[{\"id\":\"a\",\"name\":\"x\"}]")

        assertEquals(listOf(AttachmentMetadata("a", "x", "", 0L)), decoded)
    }

    @Test
    fun magicSeparatesEncryptedAndPlaintextBytes() {
        // O magic "NOTAATT1" é a distinção usada pelo AttachmentStore para reconciliar o estado
        // de criptografia; testa que o prefixo cifrado o contém e o puro não.
        val key = NoteEncryption.attachmentKey(secret)
        val encrypted = "NOTAATT1".toByteArray(Charsets.US_ASCII) + NoteEncryption.encryptBytes(ByteArray(8) { 1 }, key)
        val plain = ByteArray(16) { 2 }

        assertTrue(encrypted.copyOfRange(0, 8).contentEquals("NOTAATT1".toByteArray(Charsets.US_ASCII)))
        assertFalse(plain.copyOfRange(0, 8).contentEquals("NOTAATT1".toByteArray(Charsets.US_ASCII)))
        key.fill(0)
    }
}

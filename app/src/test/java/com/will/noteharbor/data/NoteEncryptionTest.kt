package com.will.noteharbor.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class NoteEncryptionTest {
    private val originalDeriver = NoteEncryption.keyDeriver

    @Before
    fun setUp() {
        // Argon2 usa JNI e não carrega no JVM de host; injeta um KDF determinístico puro-JVM
        // para exercitar o envelope e o AES-GCM sem a lib nativa.
        NoteEncryption.keyDeriver = { password, salt, _, _, _ ->
            val md = MessageDigest.getInstance("SHA-256")
            md.update(salt)
            md.update(password)
            md.digest()
        }
    }

    @After
    fun tearDown() {
        NoteEncryption.keyDeriver = originalDeriver
    }

    @Test
    fun encryptThenDecryptReturnsOriginalContent() {
        val items = listOf(ChecklistItem("Café", completed = true), ChecklistItem("Leite"))

        val envelope = NoteEncryption.encrypt("Conteúdo secreto", items, "senha-forte")
        val decrypted = NoteEncryption.decrypt(envelope, "senha-forte")

        assertEquals("Conteúdo secreto", decrypted.body)
        assertEquals(items, decrypted.items)
    }

    @Test
    fun decryptWithWrongPasswordThrows() {
        val envelope = NoteEncryption.encrypt("segredo", emptyList(), "senha-certa")

        try {
            NoteEncryption.decrypt(envelope, "senha-errada")
            fail("Esperava NoteDecryptionException")
        } catch (expected: NoteDecryptionException) {
            // esperado
        }
    }

    @Test
    fun envelopeDoesNotContainPlaintext() {
        val envelope = NoteEncryption.encrypt(
            "chave-bitcoin-secreta",
            listOf(ChecklistItem("item secreto")),
            "senha",
        )

        val decoded = String(Base64.getDecoder().decode(envelope), Charsets.UTF_8)

        assertFalse(decoded.contains("chave-bitcoin-secreta"))
        assertFalse(decoded.contains("item secreto"))
    }

    @Test
    fun envelopeHeaderCarriesArgon2idParameters() {
        val envelope = NoteEncryption.encrypt("x", emptyList(), "senha")
        val json = JSONObject(String(Base64.getDecoder().decode(envelope), Charsets.UTF_8))

        assertEquals(NoteEncryption.ENVELOPE_VERSION, json.getInt("v"))
        assertEquals("argon2id", json.getString("kdf"))
        assertEquals("AES-256-GCM", json.getString("alg"))
        assertEquals(NoteEncryption.ARGON2_T, json.getInt("t"))
        assertEquals(NoteEncryption.ARGON2_M_KIB, json.getInt("m"))
        assertEquals(NoteEncryption.ARGON2_P, json.getInt("p"))
    }

    @Test
    fun sameContentProducesDistinctEnvelopes() {
        val a = NoteEncryption.encrypt("mesmo", emptyList(), "senha")
        val b = NoteEncryption.encrypt("mesmo", emptyList(), "senha")

        assertNotEquals(a, b)
    }

    @Test
    fun realArgon2RoundTripWhenNativeAvailable() {
        Assume.assumeTrue(NoteEncryption.argon2Available)

        NoteEncryption.keyDeriver = originalDeriver
        val envelope = NoteEncryption.encrypt("segredo", emptyList(), "senha")
        val decrypted = NoteEncryption.decrypt(envelope, "senha")

        assertEquals("segredo", decrypted.body)
    }
}

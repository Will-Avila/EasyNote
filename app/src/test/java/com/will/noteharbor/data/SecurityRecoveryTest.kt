package com.will.noteharbor.data

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class SecurityRecoveryTest {
    private val originalDeriver = NoteEncryption.keyDeriver

    @Before
    fun setUp() {
        // Argon2 usa JNI e não carrega no JVM de host; injeta um KDF determinístico puro-JVM.
        NoteEncryption.keyDeriver = { password, salt, _, _, _ ->
            MessageDigest.getInstance("SHA-256").apply {
                update(salt)
                update(password)
            }.digest()
        }
    }

    @After
    fun tearDown() {
        NoteEncryption.keyDeriver = originalDeriver
    }

    @Test
    fun payloadRoundTripsAllSupportedTypes() {
        val map = linkedMapOf<String, Any?>(
            "unlock_method" to "PIN",
            "pin_salt" to "c2FsdA==",
            "pin_verifier" to "dmVyaWZpZXI=",
            "wrapped.nota-1" to "aXY6Y3Q=",
            "totp-recovery.0" to "aGFzaDA=",
            "totp-skew" to 2L,
            "app_lock_enabled" to true,
        )

        val parsed = SecurityRecovery.parsePayload(SecurityRecovery.buildPayload(map, "b3RwLXNlY3JldA=="))

        assertEquals("PIN", parsed.map["unlock_method"])
        assertEquals("c2FsdA==", parsed.map["pin_salt"])
        assertEquals("dmVyaWZpZXI=", parsed.map["pin_verifier"])
        assertEquals("aXY6Y3Q=", parsed.map["wrapped.nota-1"])
        assertEquals("aGFzaDA=", parsed.map["totp-recovery.0"])
        assertEquals(2L, parsed.map["totp-skew"])
        assertEquals(true, parsed.map["app_lock_enabled"])
        assertEquals("b3RwLXNlY3JldA==", parsed.totpSecretB64)
    }

    @Test
    fun totpSecretIsExtractedNotKeptInMap() {
        val parsed = SecurityRecovery.parsePayload(
            SecurityRecovery.buildPayload(mapOf("unlock_method" to "TOTP"), "c2VjcmV0LXNlY3JldA=="),
        )

        assertEquals("c2VjcmV0LXNlY3JldA==", parsed.totpSecretB64)
        assertEquals(setOf("unlock_method"), parsed.map.keys)
    }

    @Test
    fun noteSecretKeysRoundTripInPayload() {
        // O segredo recuperável de cada nota protegida viaja no pacote sob `note-secret.<id>`
        // (base64). É o que permite re-embrulhar notas protegidas por biometria após a restauração.
        val parsed = SecurityRecovery.parsePayload(
            SecurityRecovery.buildPayload(
                mapOf("unlock_method" to "BIOMETRIC", "note-secret.nota-1" to "c2VjcmV0bw=="),
                null,
            ),
        )

        assertEquals("BIOMETRIC", parsed.map["unlock_method"])
        assertEquals("c2VjcmV0bw==", parsed.map["note-secret.nota-1"])
    }

    @Test
    fun emptyPayloadParsesToEmpty() {
        val parsed = SecurityRecovery.parsePayload(SecurityRecovery.buildPayload(emptyMap(), null))

        assertTrue(parsed.map.isEmpty())
        assertNull(parsed.totpSecretB64)
    }

    @Test
    fun malformedLinesAreIgnored() {
        val raw = "unlock_method|S|PIN\n\nincompleta\ntotp-skew|L|nao-numero\n"

        val parsed = SecurityRecovery.parsePayload(raw)

        assertEquals(mapOf("unlock_method" to "PIN"), parsed.map)
    }

    @Test
    fun unknownValueTypesAreSkipped() {
        val parsed = SecurityRecovery.parsePayload(
            SecurityRecovery.buildPayload(mapOf("ok" to "sim", "numero" to 42), null),
        )

        assertEquals(mapOf("ok" to "sim"), parsed.map)
    }

    @Test
    fun deriveKeyIsDeterministicAnd32Bytes() {
        val a = SecurityRecovery.deriveKey("senha-segura-123")
        val b = SecurityRecovery.deriveKey("senha-segura-123")

        assertArrayEquals(a, b)
        assertEquals(32, a.size)
    }

    @Test
    fun deriveKeyDependsOnPassphrase() {
        val a = SecurityRecovery.deriveKey("senha-segura-123")
        val c = SecurityRecovery.deriveKey("outra-senha-456")

        assertFalse(a.contentEquals(c))
    }

    @Test
    fun envelopeRoundTripsWithMatchingPassphrase() {
        val key = SecurityRecovery.deriveKey("minha-senha-de-recuperacao")
        val payload = SecurityRecovery.buildPayload(
            mapOf("unlock_method" to "PIN", "wrapped.nota-1" to "aXY6Y3Q="),
            "dG90cC1zZWNyZXQ=",
        )
        val ciphertext = NoteEncryption.encryptBytes(payload.toByteArray(Charsets.UTF_8), key)
        val raw = "NOTSRC1".toByteArray(Charsets.US_ASCII) + byteArrayOf(1) + ciphertext
        val envelope = Base64.getEncoder().withoutPadding().encodeToString(raw)

        // Simula o restore: rederiva a chave da senha, decifra e confere o conteúdo.
        val restoreKey = SecurityRecovery.deriveKey("minha-senha-de-recuperacao")
        val decoded = Base64.getDecoder().decode(envelope)
        val magic = "NOTSRC1".toByteArray(Charsets.US_ASCII)
        val plain = NoteEncryption.decryptBytes(decoded.copyOfRange(magic.size + 1, decoded.size), restoreKey)
        val parsed = SecurityRecovery.parsePayload(String(plain, Charsets.UTF_8))

        assertEquals("PIN", parsed.map["unlock_method"])
        assertEquals("aXY6Y3Q=", parsed.map["wrapped.nota-1"])
        assertEquals("dG90cC1zZWNyZXQ=", parsed.totpSecretB64)
    }

    @Test
    fun envelopeFailsWithWrongPassphrase() {
        val key = SecurityRecovery.deriveKey("senha-certa-123")
        val payload = SecurityRecovery.buildPayload(mapOf("unlock_method" to "PIN"), null)
        val ciphertext = NoteEncryption.encryptBytes(payload.toByteArray(Charsets.UTF_8), key)
        val raw = "NOTSRC1".toByteArray(Charsets.US_ASCII) + byteArrayOf(1) + ciphertext

        val wrongKey = SecurityRecovery.deriveKey("senha-errada-999")
        val magic = "NOTSRC1".toByteArray(Charsets.US_ASCII)
        var threw = false
        try {
            NoteEncryption.decryptBytes(raw.copyOfRange(magic.size + 1, raw.size), wrongKey)
        } catch (_: Exception) {
            threw = true
        }

        assertTrue("GCM tag deveria falhar com senha errada", threw)
    }

    @Test
    fun isPassphraseEnvelopeDistinguishesMagic() {
        val ciphertext = NoteEncryption.encryptBytes("payload".toByteArray(), ByteArray(32) { 7 })
        val b64 = { magic: String ->
            Base64.getEncoder().withoutPadding()
                .encodeToString(magic.toByteArray(Charsets.US_ASCII) + byteArrayOf(1) + ciphertext)
        }

        // Envelope portável (senha de recuperação) é o único que o diálogo consegue decifrar.
        assertTrue(SecurityRecovery.isPassphraseEnvelope(b64("NOTSRC1")))
        // Envelope local (chave do aparelho) não é passível de senha.
        assertFalse(SecurityRecovery.isPassphraseEnvelope(b64("NOTSRC2")))
        // Lixo não é envelope.
        assertFalse(SecurityRecovery.isPassphraseEnvelope("@@@"))
        assertFalse(SecurityRecovery.isPassphraseEnvelope(""))
    }
}

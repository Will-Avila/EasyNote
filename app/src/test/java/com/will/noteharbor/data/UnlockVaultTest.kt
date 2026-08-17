package com.will.noteharbor.data

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class UnlockVaultTest {
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
    fun pinKeyIsDeterministicAnd32Bytes() {
        val salt = ByteArray(16) { it.toByte() }

        val a = UnlockVault.derivePinKey("1234", salt)
        val b = UnlockVault.derivePinKey("1234", salt)

        assertEquals(32, a.size)
        assertArrayEquals(a, b)
    }

    @Test
    fun wrappedPasswordRoundTripsWithMatchingKey() {
        val key = ByteArray(32) { it.toByte() }

        val stored = UnlockVault.wrapPasswordWithKey("senha-da-nota", key)

        assertEquals("senha-da-nota", UnlockVault.unwrapPasswordWithKey(stored, key))
    }

    @Test
    fun wrappedPasswordFailsWithWrongKey() {
        val key = ByteArray(32) { it.toByte() }
        val wrong = ByteArray(32) { (it + 1).toByte() }

        val stored = UnlockVault.wrapPasswordWithKey("senha-da-nota", key)

        assertNull(UnlockVault.unwrapPasswordWithKey(stored, wrong))
    }

    @Test
    fun wrappedPasswordDoesNotLeakPlaintext() {
        val key = ByteArray(32) { it.toByte() }

        val stored = UnlockVault.wrapPasswordWithKey("chave-secreta", key)

        assert(!stored.contains("chave-secreta"))
    }

    @Test
    fun recoveryCodeHasExpectedFormat() {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        repeat(50) {
            val code = UnlockVault.newRecoveryCode()
            assertEquals(12, code.length)
            assert(code.all { it in alphabet })
        }
    }

    @Test
    fun recoveryCodeNormalizationStripsSeparatorsAndUppercases() {
        assertEquals("A1B2C3D4E5F6", UnlockVault.normalizeRecoveryCode("a1b2-c3d4-e5f6"))
        assertEquals("A1B2C3D4E5F6", UnlockVault.normalizeRecoveryCode("  a1b2 c3d4e5f6 "))
        assertEquals("ABCDEFGHJKMN", UnlockVault.normalizeRecoveryCode("ABCDEFGHJKMN"))
    }

    @Test
    fun recoveryCodeNormalizationRejectsWrongLength() {
        assertNotEquals("ABCDEFGHJKMN", UnlockVault.normalizeRecoveryCode("ABC"))
    }
}

package com.will.noteharbor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpTest {
    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    @Test
    fun generatesKnownRfc6238Vectors() {
        // Vetores do RFC 6238 (SHA1, 6 dígitos) — tempos em milissegundos.
        assertEquals("287082", Totp.generate(rfcSecret, 59_000))
        assertEquals("081804", Totp.generate(rfcSecret, 1_111_111_109_000))
        assertEquals("050471", Totp.generate(rfcSecret, 1_111_111_111_000))
        assertEquals("005924", Totp.generate(rfcSecret, 1_234_567_890_000))
        assertEquals("279037", Totp.generate(rfcSecret, 2_000_000_000_000))
        assertEquals("353130", Totp.generate(rfcSecret, 20_000_000_000_000))
    }

    @Test
    fun verifyAcceptsCurrentAndNeighborSteps() {
        val now = 1_234_567_890_000L
        val code = Totp.generate(rfcSecret, now)
        assertTrue(Totp.verify(rfcSecret, code, now))
        assertTrue(Totp.verify(rfcSecret, code, now + 30_000)) // passo seguinte
        assertTrue(Totp.verify(rfcSecret, code, now - 30_000)) // passo anterior
    }

    @Test
    fun verifyRejectsWrongCodeAndBadFormat() {
        val now = 1_234_567_890_000L
        assertFalse(Totp.verify(rfcSecret, "000000", now))
        assertFalse(Totp.verify(rfcSecret, "12345", now)) // comprimento errado
        assertFalse(Totp.verify(rfcSecret, "abcdef", now)) // não numérico
        assertFalse(Totp.verify(rfcSecret, "", now))
    }

    @Test
    fun findSkewFindsTheClockOffset() {
        val now = 1_234_567_890_000L
        // Autenticador 2 min à frente do relógio deste aparelho.
        val ahead = Totp.generate(rfcSecret, now + 120_000)
        assertEquals(120_000L, Totp.findSkew(rfcSecret, ahead, now))
        // Autenticador 3 min atrás.
        val behind = Totp.generate(rfcSecret, now - 180_000)
        assertEquals(-180_000L, Totp.findSkew(rfcSecret, behind, now))
        assertNull(Totp.findSkew(rfcSecret, "000000", now))
        assertNull(Totp.findSkew(rfcSecret, "abc123", now))
    }

    @Test
    fun verifyAcceptsCodesWithinUnlockWindow() {
        val now = 1_234_567_890_000L
        val ahead = Totp.generate(rfcSecret, now + 1 * 30_000) // 30s à frente
        val behind = Totp.generate(rfcSecret, now - 1 * 30_000) // 30s atrás
        val tooFar = Totp.generate(rfcSecret, now + 4 * 30_000) // 2 min à frente
        assertTrue(Totp.verify(rfcSecret, ahead, now, window = Totp.UNLOCK_WINDOW))
        assertTrue(Totp.verify(rfcSecret, behind, now, window = Totp.UNLOCK_WINDOW))
        assertFalse(Totp.verify(rfcSecret, tooFar, now, window = Totp.UNLOCK_WINDOW))
        assertFalse(Totp.verify(rfcSecret, "000000", now, window = Totp.UNLOCK_WINDOW))
    }

    @Test
    fun base32EncodesKnownVectorWithoutPadding() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.base32(rfcSecret))
    }

    @Test
    fun newSecretIsTwentyBytesAndRandom() {
        val a = Totp.newSecret()
        val b = Totp.newSecret()
        assertEquals(20, a.size)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun otpauthUriHasRequiredFields() {
        val uri = Totp.otpauthUri("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertTrue(uri.startsWith("otpauth://totp/"))
        assertTrue(uri.contains("secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
        assertTrue(uri.contains("algorithm=SHA1"))
        assertTrue(uri.contains("digits=6"))
        assertTrue(uri.contains("period=30"))
    }
}

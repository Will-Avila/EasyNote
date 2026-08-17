package com.will.noteharbor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResetTest {
    @Test
    fun randomConfirmationWordReturnsMemberOfList() {
        repeat(100) {
            assertTrue(AppReset.randomConfirmationWord() in AppReset.WORDS)
        }
    }

    @Test
    fun matchesAcceptsExactUppercase() {
        assertTrue(AppReset.matches("FOGUETE", "FOGUETE"))
    }

    @Test
    fun matchesIgnoresCase() {
        assertTrue(AppReset.matches("FOGUETE", "fOgUeTe"))
    }

    @Test
    fun matchesTrimsWhitespace() {
        assertTrue(AppReset.matches("FOGUETE", "  FOGUETE "))
    }

    @Test
    fun matchesStripsAccentsFromTyped() {
        assertEquals("FOGUETE", AppReset.normalize("fôguête"))
        assertTrue(AppReset.matches("FOGUETE", "foguete"))
    }

    @Test
    fun matchesRejectsWrongWord() {
        assertFalse(AppReset.matches("FOGUETE", "CACHORRO"))
    }

    @Test
    fun matchesRejectsPrefix() {
        assertFalse(AppReset.matches("FOGUETE", "FOGU"))
    }

    @Test
    fun matchesRejectsEmptyAndBlank() {
        assertFalse(AppReset.matches("FOGUETE", ""))
        assertFalse(AppReset.matches("FOGUETE", "   "))
    }
}

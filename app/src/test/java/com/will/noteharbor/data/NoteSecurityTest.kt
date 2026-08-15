package com.will.noteharbor.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSecurityTest {
    @Test
    fun hashDoesNotContainTheOriginalPassword() {
        val password = "segredo-123"

        val stored = NoteSecurity.hash(password)

        assertFalse(stored.contains(password))
        assertTrue(stored.contains("$"))
    }

    @Test
    fun matchesOnlyThePasswordUsedToCreateTheHash() {
        val stored = NoteSecurity.hash("segredo-123")

        assertTrue(NoteSecurity.matches("segredo-123", stored))
        assertFalse(NoteSecurity.matches("outra-senha", stored))
    }
}

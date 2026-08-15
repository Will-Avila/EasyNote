package com.will.noteharbor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteMigrationPolicyTest {
    @Test
    fun freshInstallStartsWithNoSavedNotes() {
        assertTrue(NoteMigrationPolicy.notesForInitialStore(null).isEmpty())
    }

    @Test
    fun validLegacyNotesArePreservedDuringMigration() {
        val legacyNote = Note(id = "legacy", title = "Minha nota")

        assertEquals(
            listOf(legacyNote),
            NoteMigrationPolicy.notesForInitialStore(listOf(legacyNote)),
        )
    }
}

package com.will.noteharbor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDatabaseMappingTest {
    @Test
    fun mapsNotesAndChecklistItemsToEncryptedDatabaseRowsAndBack() {
        val original = Note(
            id = "note-1",
            title = "Compras",
            body = "",
            type = NoteType.CHECKLIST,
            color = NoteColor.MINT,
            pinned = true,
            archived = false,
            locked = true,
            passwordHash = "salt\$hash",
            updatedAt = 1234L,
            updatedBy = "device-a",
            reminder = ReminderSchedule.monthly(7, 45, dayOfMonth = 15),
            items = listOf(
                ChecklistItem("Café", completed = true),
                ChecklistItem("Leite"),
            ),
        )
        val snapshot = NoteStoreSnapshot(
            notes = listOf(original),
            tombstones = mapOf("deleted" to 9999L),
        )

        val (noteRows, itemRows, tombstoneRows) = snapshot.toEntities()
        val restored = noteRows.toNotes(itemRows).single()

        assertEquals(original, restored)
        assertEquals("deleted", tombstoneRows.single().noteId)
        assertEquals(9999L, tombstoneRows.single().deletedAt)
        assertTrue(restored.locked)
        assertFalse(restored.items[1].completed)
    }
}

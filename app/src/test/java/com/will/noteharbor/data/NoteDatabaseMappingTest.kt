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

    @Test
    fun mapsEncryptedContentColumnRoundTrip() {
        val original = Note(
            id = "locked-2",
            title = "Segredo",
            body = "",
            type = NoteType.TEXT,
            color = NoteColor.ROSE,
            locked = true,
            passwordHash = "",
            encryptedContent = "dmVyc2lvbmFkbw==",
            updatedAt = 42L,
            updatedBy = "device-b",
            items = emptyList(),
        )
        val snapshot = NoteStoreSnapshot(notes = listOf(original), tombstones = emptyMap())

        val (noteRows, itemRows, _) = snapshot.toEntities()
        val restored = noteRows.toNotes(itemRows).single()

        assertEquals(original, restored)
        assertEquals("dmVyc2lvbmFkbw==", noteRows.single().encryptedContent)
    }

    @Test
    fun mapsTrashedAtColumnRoundTrip() {
        val original = Note(
            id = "trashed-1",
            title = "Antiga",
            body = "Conteúdo",
            trashed = true,
            trashedAt = 1700000000000L,
            updatedAt = 1700000000000L,
            updatedBy = "device-a",
        )
        val snapshot = NoteStoreSnapshot(notes = listOf(original), tombstones = emptyMap())

        val (noteRows, itemRows, _) = snapshot.toEntities()
        val restored = noteRows.toNotes(itemRows).single()

        assertEquals(original, restored)
        assertEquals(1700000000000L, noteRows.single().trashedAt)
    }

    @Test
    fun mapsAttachmentsColumnRoundTrip() {
        val original = Note(
            id = "att-1",
            title = "Com anexos",
            body = "Corpo",
            attachments = listOf(
                AttachmentMetadata("uuid-1", "foto.jpg", "image/jpeg", 2048L),
                AttachmentMetadata("uuid-2", "contrato.pdf", "application/pdf", 10485760L),
            ),
            updatedAt = 7L,
            updatedBy = "device-a",
        )
        val snapshot = NoteStoreSnapshot(notes = listOf(original), tombstones = emptyMap())

        val (noteRows, itemRows, _) = snapshot.toEntities()
        val restored = noteRows.toNotes(itemRows).single()

        assertEquals(original, restored)
        assertTrue(noteRows.single().attachments.contains("contrato.pdf"))
    }
}

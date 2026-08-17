package com.will.noteharbor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteQueriesTest {
    private val notes = listOf(
        Note(id = "1", title = "Viagem", body = "Reservar pousada", pinned = true, updatedAt = 10),
        Note(id = "2", title = "Mercado", type = NoteType.CHECKLIST, items = listOf(ChecklistItem("Café")), updatedAt = 20),
        Note(id = "3", title = "Arquivo", body = "Esconder", archived = true, updatedAt = 30),
    )

    @Test
    fun searchLooksInsideTitleAndBody() {
        val result = NoteQueries.visible(notes, "pousada", NoteFilter.ALL)

        assertEquals(listOf("1"), result.map { it.id })
    }

    @Test
    fun pinnedFilterExcludesArchivedAndUnpinnedNotes() {
        val result = NoteQueries.visible(notes, "", NoteFilter.PINNED)

        assertEquals(listOf("1"), result.map { it.id })
    }

    @Test
    fun checklistFilterReturnsOnlyChecklistNotes() {
        val result = NoteQueries.visible(notes, "", NoteFilter.CHECKLIST)

        assertTrue(result.all { it.type == NoteType.CHECKLIST })
        assertEquals(listOf("2"), result.map { it.id })
    }

    @Test
    fun checklistParserPreservesCheckedStateByPosition() {
        val previous = listOf(ChecklistItem("Um", completed = true), ChecklistItem("Dois"))

        val parsed = ChecklistParser.parse("Um\nDois", previous)

        assertEquals(listOf(true, false), parsed.map { it.completed })
    }

    @Test
    fun searchExcludesLockedNoteBody() {
        val locked = Note(id = "9", title = "Protegida", body = "segredo", locked = true, updatedAt = 40)

        val result = NoteQueries.visible(notes + locked, "segredo", NoteFilter.ALL)

        assertTrue(result.isEmpty())
    }

    @Test
    fun searchMatchesLockedNoteTitle() {
        val locked = Note(id = "9", title = "Segredo bancário", body = "nada", locked = true, updatedAt = 40)

        val result = NoteQueries.visible(notes + locked, "bancário", NoteFilter.ALL)

        assertEquals(listOf("9"), result.map { it.id })
    }

    @Test
    fun searchMatchesAttachmentNameOnUnlockedNote() {
        val withAttachment = Note(
            id = "10",
            title = "Reunião",
            body = "Pauta",
            attachments = listOf(
                AttachmentMetadata("a1", "apresentacao.pptx", "application/octet-stream", 100L),
            ),
            updatedAt = 50,
        )

        val result = NoteQueries.visible(notes + withAttachment, "apresentacao", NoteFilter.ALL)

        assertEquals(listOf("10"), result.map { it.id })
    }

    @Test
    fun searchIgnoresAttachmentNamesOnLockedNote() {
        val locked = Note(
            id = "11",
            title = "Privada",
            body = "x",
            locked = true,
            attachments = listOf(
                AttachmentMetadata("a1", "segredo-fiscal.pdf", "application/pdf", 100L),
            ),
            updatedAt = 60,
        )

        val result = NoteQueries.visible(notes + locked, "segredo-fiscal", NoteFilter.ALL)

        assertTrue(result.isEmpty())
    }
}

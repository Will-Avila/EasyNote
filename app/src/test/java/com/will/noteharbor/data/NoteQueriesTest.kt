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
}

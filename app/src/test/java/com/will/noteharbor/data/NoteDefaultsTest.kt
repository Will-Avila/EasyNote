package com.will.noteharbor.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteDefaultsTest {
    @Test
    fun blankTextNoteUsesNotaAsTitle() {
        assertEquals("Nota", NoteDefaults.titleFor("   ", NoteType.TEXT))
    }

    @Test
    fun blankChecklistUsesListaAsTitle() {
        assertEquals("Lista", NoteDefaults.titleFor("", NoteType.CHECKLIST))
    }

    @Test
    fun explicitTitleIsPreserved() {
        assertEquals("Ideias", NoteDefaults.titleFor(" Ideias ", NoteType.TEXT))
    }
}

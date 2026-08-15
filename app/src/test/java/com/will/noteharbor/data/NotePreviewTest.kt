package com.will.noteharbor.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NotePreviewTest {
    @Test
    fun textPreviewKeepsOnlyOneLine() {
        val preview = NotePreview.text("uma\nduas\ntrês\nquatro")

        assertEquals("uma …", preview)
    }

}

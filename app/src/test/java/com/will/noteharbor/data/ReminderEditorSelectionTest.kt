package com.will.noteharbor.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderEditorSelectionTest {
    @Test
    fun confirmingAScheduleMakesTheReminderEnabled() {
        val selection = ReminderEditorSelection(null)
        val schedule = ReminderSchedule.once(14, 28, LocalDate.of(2026, 8, 13))

        val updated = selection.confirm(schedule)

        assertTrue(updated.isEnabled)
        assertEquals(schedule, updated.schedule)
    }

    @Test
    fun disablingTheReminderRemovesTheSchedule() {
        val schedule = ReminderSchedule.once(14, 28, LocalDate.of(2026, 8, 13))

        val updated = ReminderEditorSelection(schedule).disable()

        assertFalse(updated.isEnabled)
        assertEquals(null, updated.schedule)
    }
}

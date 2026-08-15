package com.will.noteharbor.data

import java.time.ZonedDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderEditorDefaultsTest {
    @Test
    fun newReminderStartsOnTodayAndTheNextAvailableMinute() {
        val now = ZonedDateTime.of(2026, 8, 13, 14, 27, 42, 123_000_000, ZoneId.of("America/Fortaleza"))

        val defaults = ReminderEditorDefaults.forNewReminder(now)

        assertEquals(java.time.LocalDate.of(2026, 8, 13), defaults.date)
        assertEquals(14, defaults.hour)
        assertEquals(28, defaults.minute)
    }
}

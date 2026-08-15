package com.will.noteharbor.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderDisplayTest {
    @Test
    fun oneTimeReminderHasReadableCardFooter() {
        val schedule = ReminderSchedule.once(9, 0, LocalDate.of(2026, 8, 20))

        assertEquals("Lembrete · uma vez em 20/08/2026 às 09:00", ReminderDisplay.cardFooter(schedule))
    }

    @Test
    fun weeklyReminderListsSelectedDaysInPortuguese() {
        val schedule = ReminderSchedule.weekly(18, 30, daysOfWeek = setOf(1, 3, 7))

        assertEquals("Lembrete · seg, qua e dom às 18:30", ReminderDisplay.cardFooter(schedule))
    }

    @Test
    fun monthlyReminderIncludesTheConfiguredDay() {
        val schedule = ReminderSchedule.monthly(7, 5, dayOfMonth = 15)

        assertEquals("Lembrete · dia 15 de cada mês às 07:05", ReminderDisplay.cardFooter(schedule))
    }
}

package com.will.noteharbor.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderScheduleTest {
    private val zone = ZoneId.of("America/Fortaleza")

    @Test
    fun dailyReminderUsesTodayWhenItsTimeHasNotPassed() {
        val after = at(2026, 8, 12, 8, 30)

        val next = ReminderSchedule.daily(9, 0).nextOccurrence(after)

        assertEquals(at(2026, 8, 12, 9, 0), next)
    }

    @Test
    fun dailyReminderMovesToTomorrowWhenItsTimeHasPassed() {
        val after = at(2026, 8, 12, 9, 0)

        val next = ReminderSchedule.daily(9, 0).nextOccurrence(after)

        assertEquals(at(2026, 8, 13, 9, 0), next)
    }

    @Test
    fun weeklyReminderFindsTheNextSelectedWeekday() {
        val after = at(2026, 8, 12, 10, 0) // Wednesday

        val next = ReminderSchedule.weekly(9, 0, dayOfWeek = 1).nextOccurrence(after)

        assertEquals(at(2026, 8, 17, 9, 0), next)
    }

    @Test
    fun weeklyReminderFindsTheSoonestOfSeveralSelectedWeekdays() {
        val after = at(2026, 8, 12, 10, 0) // Wednesday

        val next = ReminderSchedule.weekly(9, 0, daysOfWeek = setOf(1, 3, 5)).nextOccurrence(after)

        assertEquals(at(2026, 8, 14, 9, 0), next)
    }

    @Test
    fun dailyReminderIsRepresentedByAllSelectedWeekdays() {
        val schedule = ReminderSchedule.weekly(9, 0, daysOfWeek = ReminderSchedule.ALL_WEEK_DAYS)

        assertEquals(ReminderSchedule.ALL_WEEK_DAYS, schedule.daysOfWeek)
        assertEquals(at(2026, 8, 12, 9, 0), schedule.nextOccurrence(at(2026, 8, 12, 8, 0)))
    }

    @Test
    fun oneTimeReminderUsesItsSpecificDate() {
        val schedule = ReminderSchedule.once(9, 0, LocalDate.of(2026, 8, 20))

        assertEquals(at(2026, 8, 20, 9, 0), schedule.nextOccurrence(at(2026, 8, 12, 8, 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oneTimeReminderRejectsPastDate() {
        ReminderSchedule.once(9, 0, LocalDate.of(2026, 8, 11)).nextOccurrence(at(2026, 8, 12, 8, 0))
    }

    @Test
    fun oneTimeReminderHasNoNextOccurrenceAfterItFires() {
        val schedule = ReminderSchedule.once(9, 0, LocalDate.of(2026, 8, 12))

        assertEquals(null, schedule.nextOccurrenceOrNull(at(2026, 8, 12, 9, 0)))
    }

    @Test
    fun monthlyReminderUsesTheLastDayWhenTheMonthIsShorter() {
        val after = at(2026, 2, 1, 8, 0)

        val next = ReminderSchedule.monthly(9, 0, dayOfMonth = 31).nextOccurrence(after)

        assertEquals(at(2026, 2, 28, 9, 0), next)
    }

    @Test
    fun monthlyReminderAdvancesToTheFollowingMonthAfterItsOccurrence() {
        val after = at(2026, 1, 31, 10, 0)

        val next = ReminderSchedule.monthly(9, 0, dayOfMonth = 31).nextOccurrence(after)

        assertEquals(at(2026, 2, 28, 9, 0), next)
    }

    @Test(expected = IllegalArgumentException::class)
    fun weeklyReminderRejectsAnInvalidDay() {
        ReminderSchedule.weekly(9, 0, dayOfWeek = 8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun monthlyReminderRejectsAnInvalidDay() {
        ReminderSchedule.monthly(9, 0, dayOfMonth = 0)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
}

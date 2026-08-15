package com.will.noteharbor.data

import java.time.LocalDate

/** User-facing reminder text that can be reused by compact note cards. */
object ReminderDisplay {
    private val weekdayLabels = mapOf(
        1 to "seg",
        2 to "ter",
        3 to "qua",
        4 to "qui",
        5 to "sex",
        6 to "sáb",
        7 to "dom",
    )

    fun cardFooter(schedule: ReminderSchedule): String = when (schedule.recurrence) {
        ReminderRecurrence.DAILY ->
            "Lembrete · todos os dias às ${formatTime(schedule.hour, schedule.minute)}"

        ReminderRecurrence.WEEKLY ->
            "Lembrete · ${formatDays(schedule.daysOfWeek)} às ${formatTime(schedule.hour, schedule.minute)}"

        ReminderRecurrence.MONTHLY ->
            "Lembrete · dia ${schedule.dayOfMonth} de cada mês às ${formatTime(schedule.hour, schedule.minute)}"

        ReminderRecurrence.ONCE ->
            "Lembrete · uma vez em ${formatDate(schedule.date)} às ${formatTime(schedule.hour, schedule.minute)}"
    }

    private fun formatDays(days: Set<Int>): String {
        val labels = days.sorted().mapNotNull(weekdayLabels::get)
        return when (labels.size) {
            0 -> "dias selecionados"
            1 -> labels.single()
            2 -> labels.joinToString(" e ")
            else -> labels.dropLast(1).joinToString(", ") + " e " + labels.last()
        }
    }

    private fun formatDate(date: LocalDate?): String = date?.let {
        "%02d/%02d/%04d".format(it.dayOfMonth, it.monthValue, it.year)
    } ?: "data específica"

    private fun formatTime(hour: Int, minute: Int): String =
        "%02d:%02d".format(hour, minute)
}

package com.will.noteharbor.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** The supported reminder patterns for a note. */
enum class ReminderRecurrence {
    DAILY,
    WEEKLY,
    MONTHLY,
    ONCE,
}

data class ReminderSchedule(
    val recurrence: ReminderRecurrence,
    val hour: Int,
    val minute: Int,
    val daysOfWeek: Set<Int> = emptySet(),
    val dayOfMonth: Int? = null,
    val date: LocalDate? = null,
) {
    init {
        require(hour in 0..23) { "O horário do lembrete deve ter uma hora entre 0 e 23" }
        require(minute in 0..59) { "O horário do lembrete deve ter minutos entre 0 e 59" }
        require(daysOfWeek.all { it in 1..7 }) { "Os dias da semana devem estar entre 1 e 7" }
        when (recurrence) {
            ReminderRecurrence.DAILY -> {
                require(daysOfWeek.isEmpty()) { "Lembretes diários não usam dias semanais" }
                require(dayOfMonth == null) { "Lembretes diários não usam dia do mês" }
                require(date == null) { "Lembretes diários não usam data específica" }
            }
            ReminderRecurrence.WEEKLY -> {
                require(daysOfWeek.isNotEmpty()) { "Escolha pelo menos um dia da semana" }
                require(dayOfMonth == null) { "Lembretes semanais não usam dia do mês" }
                require(date == null) { "Lembretes semanais não usam data específica" }
            }
            ReminderRecurrence.MONTHLY -> {
                require(daysOfWeek.isEmpty()) { "Lembretes mensais não usam dias semanais" }
                require(dayOfMonth in 1..31) { "O dia do mês deve estar entre 1 e 31" }
                require(date == null) { "Lembretes mensais não usam data específica" }
            }
            ReminderRecurrence.ONCE -> {
                require(daysOfWeek.isEmpty()) { "Lembretes únicos não usam dias semanais" }
                require(dayOfMonth == null) { "Lembretes únicos não usam dia do mês" }
                require(date != null) { "Escolha uma data específica para o lembrete" }
            }
        }
    }

    /** Returns the next occurrence, or null when a one-time reminder has expired. */
    fun nextOccurrenceOrNull(after: ZonedDateTime): ZonedDateTime? {
        val zone = after.zone
        val time = LocalTime.of(hour, minute)

        fun at(localDate: LocalDate): ZonedDateTime = ZonedDateTime.of(localDate, time, zone)

        return when (recurrence) {
            ReminderRecurrence.DAILY -> {
                val today = at(after.toLocalDate())
                if (today.isAfter(after)) today else at(after.toLocalDate().plusDays(1))
            }
            ReminderRecurrence.WEEKLY -> {
                (0L..6L)
                    .map { offset -> after.toLocalDate().plusDays(offset) }
                    .map { candidateDate ->
                        candidateDate to at(candidateDate)
                    }
                    .firstOrNull { (candidateDate, candidate) ->
                        candidateDate.dayOfWeek.value in daysOfWeek && candidate.isAfter(after)
                    }
                    ?.second
                    ?: error("Não foi possível calcular o próximo lembrete semanal")
            }
            ReminderRecurrence.MONTHLY -> {
                val firstDay = after.toLocalDate().withDayOfMonth(1)
                val thisMonth = occurrenceInMonth(firstDay, ::at)
                if (thisMonth.isAfter(after)) thisMonth
                else occurrenceInMonth(firstDay.plusMonths(1), ::at)
            }
            ReminderRecurrence.ONCE -> {
                date?.let(::at)?.takeIf { it.isAfter(after) }
            }
        }
    }

    /** Returns the next occurrence and fails clearly if a one-time reminder is already past. */
    fun nextOccurrence(after: ZonedDateTime): ZonedDateTime =
        requireNotNull(nextOccurrenceOrNull(after)) { "O lembrete de data específica já passou" }

    private fun occurrenceInMonth(
        firstDayOfMonth: LocalDate,
        at: (LocalDate) -> ZonedDateTime,
    ): ZonedDateTime {
        val day = dayOfMonth!!
            .coerceAtMost(firstDayOfMonth.lengthOfMonth())
        return at(firstDayOfMonth.withDayOfMonth(day))
    }

    companion object {
        val ALL_WEEK_DAYS: Set<Int> = (1..7).toSet()

        fun daily(hour: Int, minute: Int): ReminderSchedule =
            ReminderSchedule(ReminderRecurrence.DAILY, hour, minute)

        fun weekly(hour: Int, minute: Int, daysOfWeek: Set<Int>): ReminderSchedule =
            ReminderSchedule(ReminderRecurrence.WEEKLY, hour, minute, daysOfWeek = daysOfWeek.toSet())

        /** Compatibility factory for schedules saved by the previous UI. */
        fun weekly(hour: Int, minute: Int, dayOfWeek: Int): ReminderSchedule =
            weekly(hour, minute, setOf(dayOfWeek))

        fun monthly(hour: Int, minute: Int, dayOfMonth: Int): ReminderSchedule =
            ReminderSchedule(ReminderRecurrence.MONTHLY, hour, minute, dayOfMonth = dayOfMonth)

        fun once(hour: Int, minute: Int, date: LocalDate): ReminderSchedule =
            ReminderSchedule(ReminderRecurrence.ONCE, hour, minute, date = date)
    }
}

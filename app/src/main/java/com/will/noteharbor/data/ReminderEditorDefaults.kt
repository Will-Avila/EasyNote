package com.will.noteharbor.data

import java.time.LocalDate
import java.time.ZonedDateTime

data class ReminderEditorDefaults(
    val date: LocalDate,
    val hour: Int,
    val minute: Int,
) {
    companion object {
        fun forNewReminder(now: ZonedDateTime): ReminderEditorDefaults {
            val nextMinute = now.plusMinutes(1).withSecond(0).withNano(0)
            return ReminderEditorDefaults(
                date = nextMinute.toLocalDate(),
                hour = nextMinute.hour,
                minute = nextMinute.minute,
            )
        }
    }
}

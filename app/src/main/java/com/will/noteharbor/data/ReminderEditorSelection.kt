package com.will.noteharbor.data

data class ReminderEditorSelection(
    val schedule: ReminderSchedule?,
) {
    val isEnabled: Boolean
        get() = schedule != null

    fun confirm(schedule: ReminderSchedule): ReminderEditorSelection =
        copy(schedule = schedule)

    fun disable(): ReminderEditorSelection =
        copy(schedule = null)
}

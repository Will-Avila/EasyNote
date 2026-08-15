package com.will.noteharbor.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.will.noteharbor.data.NoteRepository
import com.will.noteharbor.data.ReminderRecurrence
import java.util.concurrent.Executors

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getStringExtra(ReminderScheduler.EXTRA_NOTE_ID) ?: return
        val retryAttempts = intent.getIntExtra(ReminderScheduler.EXTRA_RETRY_COUNT, 0)
        val pendingResult = goAsync()
        EXECUTOR.execute {
            val appContext = context.applicationContext
            val repository = runCatching { NoteRepository(appContext) }.getOrElse {
                Log.e(TAG, "Could not open notes database while delivering note=$noteId", it)
                ReminderScheduler.scheduleRetry(appContext, noteId, retryAttempts)
                pendingResult.finish()
                return@execute
            }
            try {
                val note = repository.load().firstOrNull { it.id == noteId }
                if (note?.reminder == null) {
                    ReminderScheduler.cancel(appContext, noteId)
                } else {
                    val posted = ReminderNotification.show(appContext, note)
                    if (!posted) {
                        ReminderScheduler.scheduleRetry(appContext, noteId, retryAttempts)
                    } else if (note.reminder.recurrence == ReminderRecurrence.ONCE) {
                        ReminderScheduler.cancelAlarmAndForget(appContext, noteId)
                        repository.clearReminder(noteId)
                    } else if (note.reminder.nextOccurrenceOrNull(java.time.ZonedDateTime.now()) == null) {
                        ReminderScheduler.cancel(appContext, noteId)
                    } else {
                        ReminderScheduler.schedule(appContext, note)
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Could not deliver reminder for note=$noteId", error)
                ReminderScheduler.scheduleRetry(appContext, noteId, retryAttempts)
            } finally {
                repository.close()
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val EXECUTOR = Executors.newSingleThreadExecutor()
        const val TAG = "ReminderAlarmReceiver"
    }
}

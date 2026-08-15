package com.will.noteharbor.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.will.noteharbor.data.NoteRepository
import java.util.concurrent.Executors

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESCHEDULE_ACTIONS) return
        val retryAttempts = intent.getIntExtra(ReminderScheduler.EXTRA_RECONCILE_RETRY_COUNT, 0)
        val pendingResult = goAsync()
        EXECUTOR.execute {
            val appContext = context.applicationContext
            val repository = runCatching { NoteRepository(appContext) }.getOrElse {
                Log.e(TAG, "Could not open notes database while rebuilding reminders", it)
                ReminderScheduler.scheduleReconcileRetry(appContext, retryAttempts)
                pendingResult.finish()
                return@execute
            }
            try {
                ReminderScheduler.reconcile(appContext, repository.load())
            } catch (error: Throwable) {
                Log.e(TAG, "Could not rebuild reminder alarms for action=${intent.action}", error)
                ReminderScheduler.scheduleReconcileRetry(appContext, retryAttempts)
            } finally {
                repository.close()
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val EXECUTOR = Executors.newSingleThreadExecutor()
        val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.TIME_SET",
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.USER_UNLOCKED",
            ReminderScheduler.ACTION_RECONCILE_RETRY,
        )
        const val TAG = "ReminderRescheduleReceiver"
    }
}

package com.will.noteharbor.reminder

import android.annotation.SuppressLint
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.util.Log
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.will.noteharbor.MainActivity
import com.will.noteharbor.R
import com.will.noteharbor.data.Note

object ReminderNotification {
    // The original channel could have been created with a lower importance by an
    // earlier build. Android does not allow an app to raise a channel's importance
    // after creation, so the versioned id gives existing installs a fresh channel.
    // v2 was used by the first background-delivery implementation. A new
    // channel is required because Android persists a user's importance/block
    // decision and does not let an app raise it programmatically.
    const val CHANNEL_ID = "lembretes_v3"
    const val EXTRA_NOTE_ID = "extra_reminder_note_id"
    private const val NOTIFICATION_ID_OFFSET = 10_000

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Lembretes",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notificações dos lembretes recorrentes das notas"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 120, 250)
                setShowBadge(true)
            },
        )
    }

    fun canPost(context: Context): Boolean {
        return runCatching {
            ensureChannel(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)
            channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
        }.getOrElse {
            Log.e(TAG, "Could not verify notification availability", it)
            false
        }
    }

    fun notificationId(noteId: String): Int =
        NOTIFICATION_ID_OFFSET + (noteId.hashCode() and Int.MAX_VALUE) % 1_000_000

    @SuppressLint("MissingPermission")
    fun show(context: Context, note: Note): Boolean = runCatching {
        ensureChannel(context)
        if (!canPost(context)) {
            Log.w(TAG, "Notification unavailable for note=${note.id}: permission, app notifications, or channel disabled")
            return false
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTE_ID, note.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(note.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (note.locked) "Lembrete de nota protegida" else note.title
        val body = if (note.locked) "Toque para abrir e desbloquear a nota." else "Seu lembrete está aguardando você."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(note.id), notification)
        true
    }.getOrElse {
        Log.e(TAG, "Could not publish reminder notification for note=${note.id}", it)
        false
    }

    fun cancel(context: Context, noteId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(noteId))
    }

    private const val TAG = "ReminderNotification"
}

object ReminderScheduler {
    const val ACTION_REMINDER = "com.will.noteharbor.action.REMINDER"
    const val ACTION_RECONCILE_RETRY = "com.will.noteharbor.action.RECONCILE_RETRY"
    const val EXTRA_NOTE_ID = "extra_note_id"
    const val EXTRA_RETRY_COUNT = "extra_reminder_retry_count"
    const val EXTRA_RECONCILE_RETRY_COUNT = "extra_reconcile_retry_count"
    private const val REQUEST_CODE_OFFSET = 20_000
    private const val RECONCILE_REQUEST_CODE = 19_999
    private const val RETRY_REQUEST_CODE_OFFSET = 1_020_000
    private const val RETRY_DELAY_MILLIS = 15 * 60 * 1000L
    private const val MAX_RETRY_ATTEMPTS = 4
    private const val PREFS = "noteharbor.reminders"
    private const val SCHEDULED_IDS = "scheduled-note-ids"

    fun reconcile(context: Context, notes: List<Note>) {
        val appContext = context.applicationContext
        ReminderNotification.ensureChannel(appContext)
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousIds = preferences.getStringSet(SCHEDULED_IDS, emptySet()).orEmpty()
        val activeNotes = notes.filter { !it.trashed }
        val currentById = activeNotes.associateBy { it.id }
        previousIds.filterNot { currentById.containsKey(it) || activeNotes.any { note -> note.id == it && note.reminder != null } }
            .forEach { cancel(appContext, it) }

        val scheduledIds = activeNotes.mapNotNull { note ->
            note.reminder?.let {
                if (schedule(appContext, alarmManager, note, it)) note.id else null
            }
        }.toSet()
        previousIds.filterNot(scheduledIds::contains).forEach { cancel(appContext, it) }
        preferences.edit().putStringSet(SCHEDULED_IDS, scheduledIds).apply()
    }

    fun schedule(context: Context, note: Note) {
        if (note.trashed) {
            cancel(context, note.id)
            return
        }
        val reminder = note.reminder ?: return
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        schedule(context, alarmManager, note, reminder)
    }
    fun cancel(context: Context, noteId: String) {
        cancelAlarmAndForget(context, noteId)
        ReminderNotification.cancel(context, noteId)
    }

    /** Cancels future delivery without removing an already-posted notification. */
    fun cancelAlarmAndForget(context: Context, noteId: String) {
        val appContext = context.applicationContext
        cancel(appContext, appContext.getSystemService(AlarmManager::class.java), noteId)
        cancelRetry(appContext, noteId)
        val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remainingIds = preferences.getStringSet(SCHEDULED_IDS, emptySet()).orEmpty() - noteId
        preferences.edit().putStringSet(SCHEDULED_IDS, remainingIds).apply()
    }

    private fun cancelRetry(context: Context, noteId: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_REMINDER
            data = Uri.parse("noteharbor://reminder-retry/$noteId")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            retryRequestCode(noteId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    /** Keeps a reminder alive when Android refuses the notification at delivery time. */
    fun scheduleRetry(context: Context, noteId: String, previousAttempts: Int) {
        if (previousAttempts >= MAX_RETRY_ATTEMPTS) {
            Log.e(TAG, "Reminder delivery stopped after $MAX_RETRY_ATTEMPTS retries for note=$noteId")
            return
        }
        val attempt = previousAttempts + 1
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_REMINDER
            data = Uri.parse("noteharbor://reminder-retry/$noteId")
            putExtra(EXTRA_NOTE_ID, noteId)
            putExtra(EXTRA_RETRY_COUNT, attempt)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            retryRequestCode(noteId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + RETRY_DELAY_MILLIS,
                pendingIntent,
            )
            Log.w(TAG, "Reminder delivery deferred for note=$noteId; retry $attempt/$MAX_RETRY_ATTEMPTS scheduled in 15 minutes")
        }.onFailure {
            Log.e(TAG, "Could not schedule reminder retry for note=$noteId", it)
        }
    }

    /** Reopens the persisted snapshot after a boot/reschedule database failure. */
    fun scheduleReconcileRetry(context: Context, previousAttempts: Int) {
        if (previousAttempts >= MAX_RETRY_ATTEMPTS) {
            Log.e(TAG, "Reminder reconciliation stopped after $MAX_RETRY_ATTEMPTS retries")
            return
        }
        val attempt = previousAttempts + 1
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, ReminderRescheduleReceiver::class.java).apply {
            action = ACTION_RECONCILE_RETRY
            putExtra(EXTRA_RECONCILE_RETRY_COUNT, attempt)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            RECONCILE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + RETRY_DELAY_MILLIS,
                pendingIntent,
            )
            Log.w(TAG, "Reminder reconciliation retry $attempt/$MAX_RETRY_ATTEMPTS scheduled in 15 minutes")
        }.onFailure {
            Log.e(TAG, "Could not schedule reminder reconciliation retry", it)
        }
    }

    private fun schedule(context: Context, alarmManager: AlarmManager, note: Note) {
        schedule(context, alarmManager, note, note.reminder ?: return)
    }

    private fun schedule(
        context: Context,
        alarmManager: AlarmManager,
        note: Note,
        reminder: com.will.noteharbor.data.ReminderSchedule,
    ): Boolean {
        val now = java.time.ZonedDateTime.now()
        val triggerAt = reminder.nextOccurrenceOrNull(now)
            ?.toInstant()
            ?.toEpochMilli()
            ?: if (reminder.recurrence == com.will.noteharbor.data.ReminderRecurrence.ONCE) {
                // A one-time reminder remains persisted until publication succeeds.
                // If the original alarm was missed or publication was blocked, retry
                // instead of treating the expired calendar date as completed.
                System.currentTimeMillis() + RETRY_DELAY_MILLIS
            } else {
                cancel(context, note.id)
                return false
            }
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_REMINDER
            data = Uri.parse("noteharbor://reminder/${note.id}")
            putExtra(EXTRA_NOTE_ID, note.id)
            putExtra(EXTRA_RETRY_COUNT, 0)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(note.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return runCatching {
            cancelRetry(context, note.id)
            alarmManager.cancel(pendingIntent)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            Log.i(TAG, "Reminder scheduled for note=${note.id} at $triggerAt (${reminder.recurrence})")
            true
        }.getOrElse {
            Log.e(TAG, "Could not schedule reminder for note=${note.id}", it)
            false
        }
    }

    private fun cancel(context: Context, alarmManager: AlarmManager, noteId: String) {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_REMINDER
            data = Uri.parse("noteharbor://reminder/$noteId")
            putExtra(EXTRA_NOTE_ID, noteId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(noteId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun requestCode(noteId: String): Int =
        REQUEST_CODE_OFFSET + (noteId.hashCode() and Int.MAX_VALUE) % 1_000_000

    private fun retryRequestCode(noteId: String): Int =
        RETRY_REQUEST_CODE_OFFSET + (noteId.hashCode() and Int.MAX_VALUE) % 1_000_000

    private const val TAG = "ReminderScheduler"
}

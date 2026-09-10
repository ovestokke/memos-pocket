package com.vstokke.memos.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.vstokke.memos.data.AppDatabase
import com.vstokke.memos.domain.Memo
import com.vstokke.memos.domain.ReminderRecord
import java.time.Clock
import java.time.Instant

class ReminderCoordinator(
    private val context: Context,
    private val database: AppDatabase,
    private val publisher: NotificationPublisher,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val diagnostics = context.getSharedPreferences("reminder-diagnostics", Context.MODE_PRIVATE)

    @Synchronized
    fun processDueAndSchedule() {
        val now = clock.instant()
        if (publisher.canPublish()) {
            database.pendingDue(now, CATCH_UP_SECONDS).forEach { record ->
                if (publisher.publish(record)) database.acknowledgeDelivery(record, now)
            }
        }
        scheduleNext(now)
    }

    @Synchronized
    fun scheduleNext(now: Instant = clock.instant()) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val intent = alarmIntent()
        manager.cancel(intent)
        diagnostics.edit().remove("next").apply()
        val next = database.nextDueAfter(now) ?: return
        if (!canScheduleExact()) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), intent)
        } else {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), intent)
            } catch (_: SecurityException) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), intent)
            }
        }
        diagnostics.edit().putString("next", next.toString()).apply()
    }

    @Synchronized
    fun clearReminders() {
        cancelAlarm()
        database.reconcileReminders(emptyList())
        publisher.cancelAll()
    }

    @Synchronized
    fun reconcile(records: List<ReminderRecord>) {
        // Cancel stale visible notifications without disturbing unrelated app notifications.
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith("memos/") == true }.forEach { active ->
            val record = records.find { it.memoName == active.tag }
            if (record == null || record.dueAt.toString() != active.notification.extras.getString("reminderDue")) publisher.cancel(active.tag)
        }
        database.reconcileReminders(records)
        processDueAndSchedule()
    }

    fun syncSucceeded() { diagnostics.edit().putString("lastSync", clock.instant().toString()).apply() }
    fun diagnosticText(): String = "Last successful sync: ${diagnostics.getString("lastSync", "never")}\n" +
        "Next locally scheduled alarm: ${diagnostics.getString("next", "none")}"
    fun clearDiagnostics() { diagnostics.edit().clear().apply() }

    @Synchronized
    fun memoChanged(memo: Memo) {
        database.removeReminder(memo.name)
        publisher.cancel(memo.name)
        if (memo.state == "NORMAL") memo.reminderTime?.let {
            database.upsertReminder(ReminderRecord(memo.name, it, memo.snippet.ifBlank { memo.content }))
        }
        processDueAndSchedule()
    }

    @Synchronized
    fun memoDeleted(name: String) {
        database.removeReminder(name)
        publisher.cancel(name)
        scheduleNext()
    }

    @Synchronized
    fun cancelAlarm() {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent())
        diagnostics.edit().remove("next").apply()
    }

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_REMINDER_ALARM
            data = "memos://reminders/next".toUri()
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CATCH_UP_SECONDS = 24L * 60L * 60L
        const val ACTION_REMINDER_ALARM = "com.vstokke.memos.REMINDER_ALARM"
    }
}

package com.vstokke.memos.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vstokke.memos.MainActivity
import com.vstokke.memos.R
import com.vstokke.memos.domain.ReminderRecord

class NotificationPublisher(private val context: Context) {
    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            },
        )
    }

    fun canPublish(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun publish(reminder: ReminderRecord): Boolean {
        ensureChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!canPublish()) return false

        val account = com.vstokke.memos.data.CredentialStore(context).load()?.summary()
        if (reminder.memoName.startsWith("memos/") && account == null) return false
        val content = reminder.content.toPlainPreview().ifBlank { "Open Memos Pocket to view this memo." }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                data = android.net.Uri.Builder().scheme("memos").authority("memo")
                    .appendPath(account?.baseUrl.orEmpty()).appendPath(account?.userName.orEmpty())
                    .appendPath(reminder.memoName).build()
                if (reminder.memoName.startsWith("memos/")) {
                    putExtra("memoName", reminder.memoName)
                    putExtra("memoBaseUrl", account?.baseUrl)
                    putExtra("memoUserName", account?.userName)
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Memo reminder")
            .setContentText("Open Memos Pocket to view")
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Memo reminder")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .addExtras(android.os.Bundle().apply { putString("reminderDue", reminder.dueAt.toString()) })
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        return try {
            manager.notify(reminder.memoName, 0, notification)
            true
        } catch (_: SecurityException) { false }
    }

    fun cancel(name: String) = context.getSystemService(NotificationManager::class.java).cancel(name, 0)
    fun cancelAll() = context.getSystemService(NotificationManager::class.java).cancelAll()
    fun test(): Boolean = publish(ReminderRecord("notification-test", java.time.Instant.now(),
        "Test notification. This checks Android delivery only, not server reminders or alarms."))

    fun status(): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        return "App notifications: ${if (manager.areNotificationsEnabled()) "allowed" else "blocked"}\n" +
            "Reminder channel: ${if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) "blocked" else "enabled"}"

    }

    private fun String.toPlainPreview(): String =
        replace(Regex("```[\\s\\S]*?```"), " code ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("!\\[[^]]*]]"), "")
            .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^\\s{0,3}(#{1,6}|[-*+]|>)[ \\t]+"), "")
            .replace(Regex("[*_~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(400)

    companion object {
        const val CHANNEL_ID = "memo-reminders"
    }
}

package com.vstokke.memos.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.vstokke.memos.MemosPocketApp

class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        val app = context.applicationContext as MemosPocketApp
        val account = app.container.repository.account() ?: return
        if (!account.supportsMemoReminderTime) {
            app.container.syncScheduler.ensurePeriodicSync()
            app.container.syncScheduler.enqueueRepair()
            app.container.reminders.cancelAlarm()
            return
        }
        app.container.syncScheduler.ensurePeriodicSync()
        app.container.syncScheduler.enqueueRepair()

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.reminders.processDueAndSchedule()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_USER_UNLOCKED,
        )
    }
}

package com.vstokke.memos.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.vstokke.memos.MemosPocketApp

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderCoordinator.ACTION_REMINDER_ALARM) return
        val app = context.applicationContext as MemosPocketApp
        if (app.container.repository.account()?.supportsMemoReminderTime != true) {
            app.container.reminders.cancelAlarm()
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.reminders.processDueAndSchedule()
            } finally {
                pending.finish()
            }
        }
    }
}

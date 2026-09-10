package com.vstokke.memos

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.vstokke.memos.data.AppDatabase
import com.vstokke.memos.data.CredentialStore
import com.vstokke.memos.data.MemoRepository
import com.vstokke.memos.data.MemosApi
import com.vstokke.memos.reminders.NotificationPublisher
import com.vstokke.memos.reminders.ReminderCoordinator
import com.vstokke.memos.work.SyncScheduler

class MemosPocketApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.ensureChannel()
        val account = container.repository.account()
        if (account?.supportsMemoReminderTime == true) {
            container.syncScheduler.ensurePeriodicSync()
            container.syncScheduler.enqueueRepair()
            applicationScope.launch { container.reminders.processDueAndSchedule() }
        } else {
            if (account != null) container.syncScheduler.ensurePeriodicSync()
            container.reminders.clearReminders()
        }
    }
}

class AppContainer(application: Application) {
    private val database = AppDatabase(application)
    private val credentials = CredentialStore(application)
    val notifications = NotificationPublisher(application)
    val reminders = ReminderCoordinator(application, database, notifications)
    val syncScheduler = SyncScheduler(application)
    val repository = MemoRepository(
        credentials = credentials,
        database = database,
        api = MemosApi(),
        reminders = reminders,
        syncScheduler = syncScheduler,
    )
}

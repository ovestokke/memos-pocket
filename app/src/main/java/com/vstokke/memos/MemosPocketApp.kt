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
import com.vstokke.memos.work.SyncCoordinator
import com.vstokke.memos.work.SyncScheduler
import com.vstokke.memos.work.AndroidSyncConnectivity

class MemosPocketApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, applicationScope)
        container.notifications.ensureChannel()
        container.syncCoordinator.start()
        val account = container.repository.account()
        if (account?.supportsMemoReminderTime == true) {
            container.syncScheduler.ensurePeriodicSync()
            applicationScope.launch { container.reminders.processDueAndSchedule() }
        } else {
            if (account != null) container.syncScheduler.ensurePeriodicSync()
            container.reminders.clearReminders()
        }
    }
}

class AppContainer(
    application: Application,
    private val applicationScope: CoroutineScope,
) {
    private val database = AppDatabase(application)
    private val credentials = CredentialStore(application)
    val notifications = NotificationPublisher(application)
    val reminders = ReminderCoordinator(application, database, notifications)
    val syncScheduler = SyncScheduler(application)
    val syncCoordinator: SyncCoordinator
    val repository: MemoRepository

    init {
        // The callback is captured before the coordinator is assigned, but cannot run until the
        // fully constructed application exposes the repository to callers.
        lateinit var coordinator: SyncCoordinator
        repository = MemoRepository(
            credentials = credentials,
            database = database,
            api = MemosApi(),
            reminders = reminders,
            syncScheduler = syncScheduler,
            requestSync = { coordinator.requestLocalWrite() },
            requestManualSyncCallback = { coordinator.requestManual() },
        )
        coordinator = SyncCoordinator(
            repository = repository,
            scheduler = syncScheduler,
            connectivity = AndroidSyncConnectivity(application),
            scope = applicationScope,
        )
        syncCoordinator = coordinator
    }
}

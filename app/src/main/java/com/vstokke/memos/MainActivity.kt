package com.vstokke.memos

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vstokke.memos.ui.MainViewModel
import com.vstokke.memos.ui.MemosPocketScreen
import com.vstokke.memos.ui.MemosPocketTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val requestedMemo = mutableStateOf<com.vstokke.memos.domain.NotificationTarget?>(null)
    private fun notificationTarget(intent: Intent): com.vstokke.memos.domain.NotificationTarget? {
        val baseUrl = intent.getStringExtra("memoBaseUrl") ?: return null
        val userName = intent.getStringExtra("memoUserName") ?: return null
        val memoName = intent.getStringExtra("memoName") ?: return null
        return com.vstokke.memos.domain.NotificationTarget(baseUrl, userName, memoName)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedMemo.value = notificationTarget(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedMemo.value = notificationTarget(intent)
        val container = (application as MemosPocketApp).container
        setContent {
            val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory(container.repository))
            val state by viewModel.state.collectAsStateWithLifecycle()
            val preferences = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
            var theme by remember { mutableStateOf(preferences.getString("theme", "System") ?: "System") }
            LaunchedEffect(requestedMemo.value, state.busy, state.account, state.draft) {
                val target = requestedMemo.value
                if (target != null) {
                    if (!target.matches(state.account)) {
                        requestedMemo.value = null
                        intent.removeExtra("memoName")
                    } else if (!state.busy && state.draft == null) {
                        requestedMemo.value = null
                        intent.removeExtra("memoName")
                        viewModel.open(target.memoName)
                    }
                }
            }
            var permissionRevision by remember { mutableIntStateOf(0) }
            val notificationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                permissionRevision++
                if (granted && container.repository.account()?.supportsMemoReminderTime == true) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        container.reminders.processDueAndSchedule()
                    }
                }
            }

            val permissionPreferences = remember { getSharedPreferences("permission-prompts", MODE_PRIVATE) }
            LaunchedEffect(state.account != null) {
                if (state.account != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !permissionPreferences.getBoolean("notifications-requested", false) &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    // Record before launch so recreation or a denial cannot cause a prompt loop.
                    permissionPreferences.edit().putBoolean("notifications-requested", true).apply()
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        permissionRevision++
                        viewModel.refresh()
                        if (container.repository.account()?.supportsMemoReminderTime == true) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                container.reminders.processDueAndSchedule()
                            }
                        }
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            @Suppress("UNUSED_EXPRESSION")
            permissionRevision
            val notificationsEnabled = container.notifications.canPublish()
            val exactAlarmsEnabled = container.reminders.canScheduleExact()

            val darkTheme = if (theme == "System") isSystemInDarkTheme() else theme == "Dark"
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            MemosPocketTheme(darkTheme = darkTheme) {
                MemosPocketScreen(
                    state = state,
                    model = viewModel,
                    theme = theme,
                    onTheme = { theme = it; preferences.edit().putString("theme", it).apply() },
                    diagnosticText = container.notifications.status() + "\n" + container.reminders.diagnosticText(),
                    onTestNotification = container.notifications::test,
                    onBatterySettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:$packageName".toUri())) },
                    notificationsEnabled = notificationsEnabled,
                    exactAlarmsEnabled = exactAlarmsEnabled,
                    onEnableNotifications = {
                        if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionPreferences.edit().putBoolean("notifications-requested", true).apply()
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                },
                            )
                        }
                    },
                    onEnableExactAlarms = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = "package:$packageName".toUri()
                                },
                            )
                        }
                    },
                )
            }
        }
    }
}

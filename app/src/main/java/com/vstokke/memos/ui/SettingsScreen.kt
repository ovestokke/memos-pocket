package com.vstokke.memos.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(state: MainUiState, theme: String, onTheme: (String) -> Unit, diagnostics: String,
    notificationsEnabled: Boolean, exactAlarmsEnabled: Boolean, onNotifications: () -> Unit,
    onExact: () -> Unit, onTest: () -> Boolean, onBattery: () -> Unit, onRefresh: () -> Unit, onLogout: () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Notifications", style = MaterialTheme.typography.titleMedium)
        Text("Server reminder support (last confirmed): ${if (state.account?.supportsMemoReminderTime == true) "available" else "unavailable"}")
        if (state.account?.supportsMemoReminderTime != true) Text("This server does not advertise reminderTime support. Server reminders are disabled; a test notification can still check Android permissions.")
        Text(diagnostics, style = MaterialTheme.typography.bodyMedium)
        Text("Delivery: ${if (notificationsEnabled) "allowed by Android" else "blocked by permission or channel"}")
        Text("Exact alarms: ${if (exactAlarmsEnabled) "allowed" else "not allowed; reminders may be late"}")
        TextButton(onClick = onNotifications) { Text("Notification permission and channel settings") }
        TextButton(onClick = onExact) { Text("Exact alarm settings") }
        TextButton(onClick = onBattery) { Text("Battery and background settings") }
        OutlinedButton(onClick = { testResult = if (onTest()) "Test submitted to Android. Check the notification shade. This does not test server storage, sync or scheduled delivery." else "Test blocked. Enable app notifications and the reminder channel, then try again." }) { Text("Test notification") }
        testResult?.let { Text(it) }
        TextButton(onClick = onRefresh, enabled = !state.busy) { Text("Sync and check server support") }
        Text("WorkManager sync is approximate, not guaranteed every 15 minutes. Force-stop prevents background work. Offline delivery uses the last synchronized reminder; external edits are detected only after successful sync. Catch-up covers the previous 24 hours.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        listOf("System", "Light", "Dark").forEach { choice ->
            Row { RadioButton(selected = theme == choice, onClick = { onTheme(choice) }); TextButton(onClick = { onTheme(choice) }) { Text(choice) } }
        }
        HorizontalDivider()
        Text("About", style = MaterialTheme.typography.titleMedium)
        Text("Memos Pocket $version · Unofficial third-party Memos client", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Text("Account", style = MaterialTheme.typography.titleMedium)
        Text(state.account?.baseUrl ?: "")
        Text(state.account?.displayName ?: "")
        Text(state.account?.userName ?: "", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { confirmLogout = true }, enabled = !state.busy) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("Disconnect this account?") },
        text = { Text("Removes this app’s credentials, cached memos, reminders and active notifications. Server memos are not deleted.") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }, enabled = !state.busy) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } })
}

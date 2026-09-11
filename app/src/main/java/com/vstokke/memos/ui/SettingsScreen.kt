package com.vstokke.memos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    state: MainUiState,
    theme: String,
    onTheme: (String) -> Unit,
    diagnostics: String,
    notificationsEnabled: Boolean,
    exactAlarmsEnabled: Boolean,
    onNotifications: () -> Unit,
    onExact: () -> Unit,
    onTest: () -> Boolean,
    onBattery: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val version = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SettingsSectionTitle("Notifications")
        SettingsGroup {
            SettingsRow(
                icon = Icons.Outlined.Notifications,
                title = "App notifications",
                supporting = if (notificationsEnabled) "Allowed by Android" else "Blocked by permission or channel",
                status = if (notificationsEnabled) "On" else "Off",
                onClick = onNotifications,
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Outlined.Alarm,
                title = "Exact alarms",
                supporting = if (exactAlarmsEnabled) "Reminders can arrive on time" else "Not allowed; reminders may be late",
                status = if (exactAlarmsEnabled) "On" else "Off",
                onClick = onExact,
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Outlined.BatterySaver,
                title = "Background access",
                supporting = "Review battery restrictions",
                onClick = onBattery,
            )
        }

        SettingsSectionTitle("Check and sync")
        SettingsGroup {
            SettingsRow(
                icon = Icons.AutoMirrored.Outlined.Send,
                title = "Send test notification",
                supporting = "Checks Android permission and the reminder channel",
                onClick = {
                    testResult = if (onTest()) {
                        "Test submitted to Android. Check the notification shade. This does not test server storage, sync or scheduled delivery."
                    } else {
                        "Test blocked. Enable app notifications and the reminder channel, then try again."
                    }
                },
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Outlined.Sync,
                title = "Sync now",
                supporting = "Refresh memos and check server reminder support",
                enabled = !state.busy,
                onClick = onRefresh,
            )
        }
        testResult?.let {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
        }

        SettingsSectionTitle("Appearance")
        SettingsGroup {
            listOf("System", "Light", "Dark").forEachIndexed { index, choice ->
                SettingsRow(
                    icon = Icons.Outlined.Palette,
                    title = choice,
                    trailing = {
                        RadioButton(selected = theme == choice, onClick = null)
                    },
                    onClick = { onTheme(choice) },
                )
                if (index != 2) SettingsDivider()
            }
        }

        SettingsSectionTitle("About")
        SettingsGroup {
            SettingsRow(
                icon = Icons.Outlined.Info,
                title = "Memos Pocket $version",
                supporting = "Unofficial third-party Memos client",
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Outlined.Cloud,
                title = "Server reminders",
                supporting = if (state.account?.supportsMemoReminderTime == true) {
                    "Supported by this server"
                } else {
                    "Not advertised by this server"
                },
                status = if (state.account?.supportsMemoReminderTime == true) "Available" else "Unavailable",
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Outlined.BugReport,
                title = "Diagnostics",
                supporting = "Sync, channel and scheduled-alarm details",
                trailing = {
                    Icon(
                        if (diagnosticsOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                    )
                },
                onClick = { diagnosticsOpen = !diagnosticsOpen },
            )
            if (diagnosticsOpen) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            diagnostics,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Background sync is approximate. Force-stop prevents background work. Offline delivery uses the last synchronized reminder; external edits are found after the next successful sync. Catch-up covers the previous 24 hours.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SettingsSectionTitle("Account")
        SettingsGroup {
            SettingsRow(
                icon = Icons.Outlined.AccountCircle,
                title = state.account?.displayName ?: state.account?.userName ?: "Account",
                supporting = listOfNotNull(state.account?.userName, state.account?.baseUrl).joinToString("\n"),
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Outlined.Logout,
                title = "Disconnect",
                supporting = "Remove this account and its local data from the app",
                destructive = true,
                enabled = !state.busy,
                onClick = { confirmLogout = true },
            )
        }

        Box(Modifier.padding(bottom = 28.dp))
    }

    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("Disconnect this account?") },
        text = { Text("Removes this app’s credentials, cached memos, reminders and active notifications. Server memos are not deleted.") },
        confirmButton = {
            TextButton(
                onClick = {
                    confirmLogout = false
                    onLogout()
                },
                enabled = !state.busy,
            ) { Text("Disconnect") }
        },
        dismissButton = {
            TextButton(onClick = { confirmLogout = false }) { Text("Cancel") }
        },
    )
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector?,
    title: String,
    supporting: String? = null,
    status: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val contentColor = when {
        destructive -> MaterialTheme.colorScheme.error
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val modifier = if (onClick != null) {
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }

    ListItem(
        headlineContent = { Text(title, color = contentColor) },
        supportingContent = supporting?.let {
            {
                Text(
                    it,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                )
            }
        },
        leadingContent = icon?.let {
            {
                Icon(
                    it,
                    contentDescription = null,
                    tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = when {
            trailing != null -> trailing
            status != null -> {
                {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            onClick != null -> {
                {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}

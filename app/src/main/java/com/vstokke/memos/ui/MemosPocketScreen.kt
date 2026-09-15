package com.vstokke.memos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vstokke.memos.domain.MemoSyncStatus
import com.vstokke.memos.domain.SyncPhase
import com.vstokke.memos.domain.Space

private data class AppDestination(val label: String, val icon: ImageVector)

private val AppDestinations = listOf(
    AppDestination("Memos", Icons.Outlined.Home),
    AppDestination("Archived", Icons.Outlined.Archive),
    AppDestination("Settings", Icons.Outlined.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemosPocketScreen(
    state: MainUiState,
    model: MainViewModel,
    theme: String,
    onTheme: (String) -> Unit,
    diagnosticText: String,
    notificationsEnabled: Boolean,
    exactAlarmsEnabled: Boolean,
    onEnableNotifications: () -> Unit,
    onEnableExactAlarms: () -> Unit,
    onTestNotification: () -> Boolean,
    onBatterySettings: () -> Unit,
) {
    var settingsOpen by rememberSaveable(state.account?.baseUrl, state.account?.userName) {
        mutableStateOf(false)
    }
    val destination = if (settingsOpen) "Settings" else if (state.archive) "Archived" else "Memos"
    var confirmReload by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    fun closeEditor() {
        if (state.draft?.changed == true) confirmDiscard = true else model.discardDraft()
    }

    fun navigate(label: String) {
        if (model.state.value.draft?.base != null) return
        model.closeDetail()
        if (label == "Settings") settingsOpen = true
        else {
            settingsOpen = false
            model.page(label == "Archived")
        }
    }

    BackHandler(state.draft != null || state.detail != null || destination == "Settings") {
        when {
            destination == "Settings" -> settingsOpen = false
            state.busy -> Unit
            state.draft != null -> closeEditor()
            state.detail != null -> model.closeDetail()
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard unsaved changes?") },
        text = { Text("Your changes have not been saved locally.") },
        confirmButton = {
            TextButton(onClick = {
                model.discardDraft()
                confirmDiscard = false
            }) { Text("Discard") }
        },
        dismissButton = {
            TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
        },
    )

    if (confirmReload) AlertDialog(
        onDismissRequest = { confirmReload = false },
        title = { Text("Load server version?") },
        text = { Text("This replaces the unsaved draft. Use Copy draft first if you want to keep its text.") },
        confirmButton = {
            TextButton(onClick = {
                confirmReload = false
                model.loadServerDraft()
            }) { Text("Load server version") }
        },
        dismissButton = {
            TextButton(onClick = { confirmReload = false }) { Text("Keep draft") }
        },
    )

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state.account == null || state.reauthenticating) {
            Column {
            if (state.reauthenticating) TextButton(onClick = model::cancelSignIn) { Text("Back to offline memos") }
            LoginScreen(
                state = state,
                onLoadOptions = model::loadSignInOptions,
                onPasswordSignIn = model::signInWithPassword,
                onSsoSignIn = model::startSso,
            )
            }
            return@Surface
        }

        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val mediumNavigation = maxWidth >= 600.dp
            val expandedNavigation = maxWidth >= 840.dp
            val twoPane = maxWidth >= 1200.dp && destination != "Settings" && state.draft?.base == null
            val detailInPane = twoPane && state.detail != null
            val showCompactNavigation = !mediumNavigation && state.draft?.base == null && state.detail == null

            val title = when {
                state.draft?.base != null -> "Edit memo"
                state.detail != null && !detailInPane -> "Memo"
                else -> destination
            }

            val scaffold: @Composable () -> Unit = {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        TopAppBar(
                            title = {
                                if (
                                    state.draft?.base == null && state.detail == null &&
                                    destination != "Settings" && (state.spaces.isNotEmpty() || state.selectedSpace != null)
                                ) {
                                    SpaceAwareTitle(
                                        section = destination,
                                        spaces = state.spaces,
                                        selectedSpace = state.selectedSpace,
                                        onSelect = model::selectSpace,
                                    )
                                } else Text(title)
                            },
                            navigationIcon = {
                                if (state.draft?.base != null || state.detail != null && !detailInPane) {
                                    IconButton(
                                        enabled = !state.busy,
                                        onClick = {
                                            if (state.draft?.base != null) closeEditor() else model.closeDetail()
                                        },
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            },
                            actions = {
                                if (state.draft?.base == null && state.detail == null && destination != "Settings") {
                                    IconButton(onClick = model::refresh, enabled = !state.sync.signInRequired) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh memos")
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                scrolledContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    },
                    bottomBar = {
                        if (showCompactNavigation) {
                            CompactNavigation(destination) { navigate(it) }
                        }
                    },
                ) { padding ->
                    Column(
                        Modifier.padding(padding).fillMaxSize().imePadding(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (state.showProgress || state.sync.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        val syncMessage = if (destination == "Settings") null else when {
                            state.sync.signInRequired -> "Sign-in required. Local memos and changes are safe. Open Settings to sign in."
                            state.syncIssues.any { it.status == MemoSyncStatus.CONFLICT } -> "Sync conflict. Both versions are kept. Resolve in Settings."
                            state.syncIssues.any { it.status == MemoSyncStatus.FAILED } -> "Some changes could not sync. Review them in Settings."
                            state.sync.pushError != null -> "Local changes could not sync: ${state.sync.pushError.userMessage()}"
                            state.sync.pullError != null && state.sync.uploadAckedThisTurn ->
                                "Changes synced, but refresh failed: ${state.sync.pullError.userMessage()}"
                            state.sync.pullError != null -> "Refresh failed: ${state.sync.pullError.userMessage()}"
                            state.sync.phase == SyncPhase.UPLOADING -> "Sending local changes…"
                            state.sync.phase == SyncPhase.RECONCILING -> "Checking the server…"
                            state.sync.failed -> "Server unavailable. You can keep writing offline."
                            state.sync.pending > 0 -> "${state.sync.pending} changes saved locally · Waiting to sync"
                            state.sync.incomplete -> "Cache limit reached. Some older memos are unavailable offline."
                            else -> null
                        }
                        syncMessage?.let { StatusBanner(it, error = false) }
                        state.error?.let {
                            StatusBanner(it, error = true)
                        }
                        if (state.conflict && state.draft?.base != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Row(
                                    Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "The server memo changed.",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { confirmReload = true }, enabled = !state.busy) {
                                        Text("Load server version")
                                    }
                                }
                            }
                        }
                        state.notice?.let { StatusBanner(it, error = false) }

                        when {
                            state.draft?.base != null -> CenteredPane {
                                EditorScreen(
                                    state.draft,
                                    state.account.supportsMemoReminderTime,
                                    state.busy,
                                    state.saving,
                                    model::updateDraft,
                                    model::save,
                                    ::closeEditor,
                                )
                            }

                            destination == "Settings" -> CenteredPane(maxWidth = 820.dp) {
                                SettingsScreen(
                                    state,
                                    theme,
                                    onTheme,
                                    diagnosticText,
                                    notificationsEnabled,
                                    exactAlarmsEnabled,
                                    onEnableNotifications,
                                    onEnableExactAlarms,
                                    onTestNotification,
                                    onBatterySettings,
                                    model::refresh,
                                    model::logout,
                                    model::resolveConflict,
                                    model::retryFailed,
                                    model::requestSignIn,
                                )
                            }

                            detailInPane -> Row(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(0.46f).fillMaxHeight()) {
                                    FeedScreen(
                                        state,
                                        model::open,
                                        model::updateDraft,
                                        model::save,
                                        model::beginEdit,
                                        model::action,
                                        { model.page(state.archive, true) },
                                        selectedMemoName = state.detail?.name,
                                        onToggleTask = model::toggleTask,
                                    )
                                }
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Box(Modifier.weight(0.54f).fillMaxHeight()) {
                                    MemoDetailScreen(
                                        requireNotNull(state.detail),
                                        state.account,
                                        state.busy,
                                        model::beginEdit,
                                        model::action,
                                        model::toggleTask,
                                    )
                                }
                            }

                            state.detail != null -> CenteredPane(maxWidth = 820.dp) {
                                MemoDetailScreen(
                                    state.detail,
                                    state.account,
                                    state.busy,
                                    model::beginEdit,
                                    model::action,
                                    model::toggleTask,
                                )
                            }

                            else -> CenteredPane {
                                FeedScreen(
                                    state,
                                    model::open,
                                    model::updateDraft,
                                    model::save,
                                    model::beginEdit,
                                    model::action,
                                    { model.page(state.archive, true) },
                                    onToggleTask = model::toggleTask,
                                )
                            }
                        }
                    }
                }
            }

            when {
                expandedNavigation -> Row(Modifier.fillMaxSize()) {
                    ExpandedNavigation(destination) { navigate(it) }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1f)) { scaffold() }
                }

                mediumNavigation -> Row(Modifier.fillMaxSize()) {
                    MediumNavigation(destination, state.draft?.base != null) { navigate(it) }
                    Box(Modifier.weight(1f)) { scaffold() }
                }

                else -> scaffold()
            }
        }
    }
}

@Composable
private fun SpaceAwareTitle(
    section: String,
    spaces: List<Space>,
    selectedSpace: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val scopeTitle = spaces.firstOrNull { it.name == selectedSpace }?.title
        ?: if (selectedSpace == null) "My memos" else "Space"

    Column {
        Text(section, style = MaterialTheme.typography.titleLarge)
        Box {
            Row(
                Modifier.clickable { expanded = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    scopeTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = "Choose space",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("My memos") },
                    trailingIcon = {
                        if (selectedSpace == null) Icon(Icons.Outlined.Check, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onSelect(null)
                    },
                )
                spaces.forEach { space ->
                    DropdownMenuItem(
                        text = { Text(space.title) },
                        trailingIcon = {
                            if (selectedSpace == space.name) Icon(Icons.Outlined.Check, contentDescription = null)
                        },
                        onClick = {
                            expanded = false
                            onSelect(space.name)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredPane(maxWidth: androidx.compose.ui.unit.Dp = 760.dp, content: @Composable () -> Unit) {
    Box(
        Modifier.widthIn(max = maxWidth).fillMaxWidth().fillMaxHeight(),
        contentAlignment = Alignment.TopCenter,
    ) {
        content()
    }
}

@Composable
private fun StatusBanner(message: String, error: Boolean) {
    val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                contentDescription = null,
            )
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CompactNavigation(selected: String, onSelect: (String) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        AppDestinations.forEach { item ->
            NavigationBarItem(
                selected = selected == item.label,
                onClick = { onSelect(item.label) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun MediumNavigation(
    selected: String,
    editing: Boolean,
    onSelect: (String) -> Unit,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        header = {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = "Memos Pocket",
                    modifier = Modifier.padding(12.dp),
                )
            }
        },
    ) {
        Spacer(Modifier.height(12.dp))
        AppDestinations.forEach { item ->
            NavigationRailItem(
                selected = selected == item.label,
                onClick = { onSelect(item.label) },
                enabled = !editing,
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun ExpandedNavigation(selected: String, onSelect: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.width(216.dp).fillMaxHeight(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Text("Memos Pocket", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            AppDestinations.forEach { item ->
                NavigationDrawerItem(
                    label = { Text(item.label) },
                    selected = selected == item.label,
                    onClick = { onSelect(item.label) },
                    icon = { Icon(item.icon, contentDescription = null) },
                    shape = MaterialTheme.shapes.medium,
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }
    }
}

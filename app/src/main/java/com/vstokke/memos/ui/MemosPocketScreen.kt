package com.vstokke.memos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
    var settingsOpen by rememberSaveable(state.account?.baseUrl, state.account?.userName) { mutableStateOf(false) }
    val destination = if (settingsOpen) "Settings" else if (state.archive) "Archived" else "Memos"
    var confirmReload by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun closeEditor() { if (state.draft?.changed == true) confirmDiscard = true else model.discardDraft() }
    BackHandler(state.draft != null || state.detail != null || destination == "Settings") {
        if (!state.busy) when {
            state.draft != null -> closeEditor()
            state.detail != null -> model.closeDetail()
            else -> settingsOpen = false
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false }, title = { Text("Discard unsaved changes?") },
        text = { Text("Your changes have not been saved to the server.") },
        confirmButton = { TextButton(onClick = { model.discardDraft(); confirmDiscard = false }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
    )
    if (confirmReload) AlertDialog(onDismissRequest = { confirmReload = false }, title = { Text("Load server version?") },
        text = { Text("This replaces the unsaved draft. Use Copy draft first if you want to keep its text.") },
        confirmButton = { TextButton(onClick = { confirmReload = false; model.loadServerDraft() }) { Text("Load server version") } },
        dismissButton = { TextButton(onClick = { confirmReload = false }) { Text("Keep draft") } })
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state.account == null) {
            LoginScreen(state, model::connect)
        } else BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 840.dp
            val navigation: @Composable () -> Unit = {
                Column(Modifier.width(200.dp).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Memos Pocket", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
                    listOf("Memos", "Archived", "Settings").forEach { item ->
                        NavigationDrawerItem(label = { Text(item) }, selected = destination == item, onClick = {
                            if (!state.busy && state.draft?.base == null) {
                                if (item != "Settings") model.page(item == "Archived")
                                settingsOpen = item == "Settings"
                                model.closeDetail()
                                scope.launch { drawer.close() }
                            }
                        })
                    }
                }
            }
            val main: @Composable () -> Unit = {
                Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
                    TopAppBar(title = { Text(when { state.draft?.base != null -> "Edit memo"; state.detail != null -> "Memo"; else -> destination }) },
                        navigationIcon = {
                            if (!wide || state.draft?.base != null || state.detail != null) TextButton(enabled = !state.busy, onClick = {
                                when {
                                    state.draft?.base != null -> closeEditor()
                                    state.detail != null -> model.closeDetail()
                                    !wide -> scope.launch { drawer.open() }
                                }
                            }) { Text(if (state.draft?.base != null || state.detail != null) "Back" else "Menu") }
                        }, actions = {
                            if (state.draft?.base == null && destination != "Settings") TextButton(onClick = model::refresh, enabled = !state.busy) { Text("Refresh") }
                        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
                }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.widthIn(max = 760.dp).padding(12.dp)) }
                        if (state.conflict && state.draft?.base != null) TextButton(onClick = { confirmReload = true }, enabled = !state.busy) { Text("Load server version") }
                        state.notice?.let { Text(it, modifier = Modifier.padding(12.dp)) }
                        Box(Modifier.widthIn(max = 760.dp).fillMaxWidth().weight(1f)) {
                            when {
                                state.draft?.base != null -> EditorScreen(state.draft, state.account.supportsMemoReminderTime, state.busy, model::updateDraft, model::save, ::closeEditor)
                                destination == "Settings" -> SettingsScreen(state, theme, onTheme, diagnosticText,
                                    notificationsEnabled, exactAlarmsEnabled, onEnableNotifications, onEnableExactAlarms,
                                    onTestNotification, onBatterySettings, model::refresh, model::logout)
                                state.detail != null -> MemoDetailScreen(state.detail, state.account, state.busy, model::beginEdit, model::action)
                                else -> FeedScreen(state, model::open, model::updateDraft, model::save, model::beginEdit, model::action,
                                    { model.page(state.archive, true) })
                            }
                        }
                    }
                }
            }
            if (wide) Row { navigation(); Box(Modifier.weight(1f)) { main() } }
            else ModalNavigationDrawer(drawerState = drawer, drawerContent = { ModalDrawerSheet { navigation() } }, gesturesEnabled = state.draft?.base == null) { main() }
        }
    }
}

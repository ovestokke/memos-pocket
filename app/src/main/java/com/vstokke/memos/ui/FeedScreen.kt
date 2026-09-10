package com.vstokke.memos.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vstokke.memos.domain.AccountSummary
import com.vstokke.memos.domain.Memo
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun FeedScreen(state: MainUiState, onOpen: (String) -> Unit,
    onDraftChange: (EditorDraft) -> Unit, onSave: () -> Unit,
    onEdit: (Memo) -> Unit, onAction: (Memo, String) -> Unit, onMore: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!state.archive) item(key = "composer") {
            InlineComposer(state.draft, state.busy, state.account?.supportsMemoReminderTime == true, onDraftChange, onSave)
        }
        if (state.feed.isEmpty() && !state.busy) item { Text(if (state.archive) "No archived memos" else "No memos", modifier = Modifier.padding(16.dp)) }
        items(state.feed, key = { it.name }) { memo ->
            Surface(shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    MemoHeader(memo, requireNotNull(state.account), state.busy, onEdit, onAction)
                    Box(Modifier.fillMaxWidth().clickable { if (!state.busy) onOpen(memo.name) }.padding(vertical = 8.dp)) {
                        MarkdownText(memo.content.take(1800))
                    }
                    memo.reminderTime?.let {
                        Text("Reminder: ${formatLocalMemoTime(it)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (state.hasMore) item { OutlinedButton(onClick = onMore, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Load more") } }
    }
}

@Composable
fun MemoDetailScreen(memo: Memo, account: AccountSummary, busy: Boolean, onEdit: (Memo) -> Unit, onAction: (Memo, String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MemoHeader(memo, account, busy, onEdit, onAction)
        SelectionContainer { MarkdownText(memo.content) }
        memo.reminderTime?.let { Text("Reminder: ${formatLocalMemoTime(it)}", style = MaterialTheme.typography.bodySmall) }
        memo.space?.let { Text("Space: $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun MemoHeader(memo: Memo, account: AccountSummary, busy: Boolean, onEdit: (Memo) -> Unit, onAction: (Memo, String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val owned = memo.creator == account.userName
    val archived = memo.state == "ARCHIVED"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text((if (memo.pinned) "Pinned · " else "") + memo.createTime.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")), style = MaterialTheme.typography.bodySmall)
            Text(memo.visibility.lowercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            TextButton(onClick = { menu = true }, enabled = !busy) { Text("More") }
            DropdownMenu(menu, { menu = false }) {
                fun act(action: String) { menu = false; onAction(memo, action) }
                if (owned && !archived) {
                    if (memo.parent == null) DropdownMenuItem(text = { Text(if (memo.pinned) "Unpin" else "Pin") }, onClick = { act("pin") })
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit(memo) })
                }
                if (!archived) {
                    DropdownMenuItem(text = { Text("Copy link") }, onClick = {
                        copy(context, account.baseUrl.trimEnd('/') + "/" + memo.name); menu = false
                    })
                    DropdownMenuItem(text = { Text("Copy content") }, onClick = { copy(context, memo.content); menu = false })
                }
                if (owned) {
                    if (memo.parent == null) DropdownMenuItem(text = { Text(if (archived) "Restore" else "Archive") }, onClick = { act("archive") })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; delete = true })
                }
            }
        }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Delete memo?") },
        text = { Text("This deletes the memo on the server. Protected relations or attachments will not be force-deleted.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { delete = false; onAction(memo, "delete") }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } })
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Memo", text))
}

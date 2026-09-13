package com.vstokke.memos.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vstokke.memos.domain.AccountSummary
import com.vstokke.memos.domain.Memo
import com.vstokke.memos.domain.MemoSyncStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun FeedScreen(
    state: MainUiState,
    onOpen: (String) -> Unit,
    onDraftChange: (EditorDraft) -> Unit,
    onSave: () -> Unit,
    onEdit: (Memo) -> Unit,
    onAction: (Memo, String) -> Unit,
    onMore: () -> Unit,
    selectedMemoName: String? = null,
) {
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    ) {
        if (!state.archive) {
            item(key = "composer") {
                InlineComposer(
                    state.draft,
                    state.busy,
                    state.saving,
                    state.selectedSpace,
                    state.account?.supportsMemoReminderTime == true,
                    onDraftChange,
                    onSave,
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        if (state.feed.isEmpty() && (!state.busy || !state.showProgress)) {
            item {
                EmptyFeed(archived = state.archive)
            }
        }

        items(state.feed.size, key = { state.feed[it].name }) { index ->
            val memo = state.feed[index]
            MemoRow(
                memo = memo,
                account = requireNotNull(state.account),
                busy = state.busy,
                selected = memo.name == selectedMemoName,
                onOpen = onOpen,
                onEdit = onEdit,
                onAction = onAction,
            )
            if (index != state.feed.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }

        if (state.hasMore) {
            item {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onMore,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Load more") }
            }
        }
    }
}

@Composable
private fun EmptyFeed(archived: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (archived) "No archived memos" else "Nothing here yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (archived) "Archived memos will appear here." else "Write above to create your first memo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemoRow(
    memo: Memo,
    account: AccountSummary,
    busy: Boolean,
    selected: Boolean,
    onOpen: (String) -> Unit,
    onEdit: (Memo) -> Unit,
    onAction: (Memo, String) -> Unit,
) {
    Surface(
        onClick = { onOpen(memo.name) },
        enabled = !busy,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MemoHeader(memo, account, busy, onEdit, onAction)
            Box(Modifier.fillMaxWidth().padding(end = 12.dp)) {
                MarkdownText(memo.content.take(1800))
            }
            memo.reminderTime?.let {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.NotificationsNone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        formatLocalMemoTime(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun MemoDetailScreen(
    memo: Memo,
    account: AccountSummary,
    busy: Boolean,
    onEdit: (Memo) -> Unit,
    onAction: (Memo, String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MemoHeader(memo, account, busy, onEdit, onAction)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SelectionContainer { MarkdownText(memo.content) }
        memo.reminderTime?.let {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.NotificationsNone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    formatLocalMemoTime(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        memo.space?.let {
            Text(
                "Space: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MemoHeader(
    memo: Memo,
    account: AccountSummary,
    busy: Boolean,
    onEdit: (Memo) -> Unit,
    onAction: (Memo, String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val owned = memo.creator == account.userName
    val archived = memo.state == "ARCHIVED"
    val created = remember(memo.createTime) {
        memo.createTime.atZone(ZoneId.systemDefault()).format(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT),
        )
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (memo.syncStatus != MemoSyncStatus.SYNCED) Text(memo.syncStatus, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (memo.pinned) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    created,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                memo.visibility.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = !busy) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Memo actions")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                fun act(action: String) {
                    menu = false
                    onAction(memo, action)
                }
                if (owned && !archived) {
                    if (memo.parent == null) {
                        DropdownMenuItem(
                            text = { Text(if (memo.pinned) "Unpin" else "Pin") },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                            onClick = { act("pin") },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menu = false
                            onEdit(memo)
                        },
                    )
                }
                if (!archived) {
                    DropdownMenuItem(
                        text = { Text("Copy link") },
                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                        onClick = {
                            copy(context, account.baseUrl.trimEnd('/') + "/" + memo.name)
                            menu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy content") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        onClick = {
                            copy(context, memo.content)
                            menu = false
                        },
                    )
                }
                if (owned) {
                    if (memo.parent == null) {
                        DropdownMenuItem(
                            text = { Text(if (archived) "Restore" else "Archive") },
                            leadingIcon = {
                                Icon(
                                    if (archived) Icons.Outlined.Restore else Icons.Outlined.Archive,
                                    contentDescription = null,
                                )
                            },
                            onClick = { act("archive") },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menu = false
                            delete = true
                        },
                    )
                }
            }
        }
    }

    if (delete) AlertDialog(
        onDismissRequest = { delete = false },
        title = { Text("Delete memo?") },
        text = { Text("This deletes the memo on the server. Protected relations or attachments will not be force-deleted.") },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    delete = false
                    onAction(memo, "delete")
                },
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = { delete = false }) { Text("Cancel") }
        },
    )
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("Memo", text))
}

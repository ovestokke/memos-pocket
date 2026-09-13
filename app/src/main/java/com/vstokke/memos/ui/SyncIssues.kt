package com.vstokke.memos.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vstokke.memos.data.PendingMemo
import com.vstokke.memos.domain.MemoSyncStatus

@Composable
fun SyncIssues(
    issues: List<PendingMemo>,
    onResolve: (String, Boolean) -> Unit,
    onRetry: (String) -> Unit,
) {
    var selected by remember { mutableStateOf<PendingMemo?>(null) }
    var choice by remember { mutableStateOf<Boolean?>(null) }
    if (issues.isNotEmpty()) {
        SettingsSectionTitle("Local changes")
        SettingsGroup {
            issues.forEachIndexed { index, issue ->
                val needsAttention = issue.status == MemoSyncStatus.CONFLICT || issue.status == MemoSyncStatus.FAILED
                SettingsRow(
                    icon = if (needsAttention) Icons.Outlined.ErrorOutline else Icons.Outlined.CloudQueue,
                    title = when (issue.status) {
                        MemoSyncStatus.CONFLICT -> "Conflict"
                        MemoSyncStatus.FAILED -> "Sync failed"
                        else -> "Waiting to sync"
                    },
                    supporting = if (issue.deleted) {
                        "Deletion saved on this device"
                    } else {
                        issue.desired.content.lineSequence().firstOrNull().orEmpty().take(100).ifBlank { "Empty memo" }
                    },
                    status = when (issue.status) {
                        MemoSyncStatus.CONFLICT -> "Resolve"
                        MemoSyncStatus.FAILED -> "Review"
                        else -> "Saved"
                    },
                    onClick = { selected = issue },
                )
                if (index != issues.lastIndex) SettingsDivider()
            }
        }
    }
    selected?.let { issue ->
        AlertDialog(
            onDismissRequest = { selected = null; choice = null },
            title = { Text(if (choice == false) "Discard local changes?" else if (choice == true) "Keep as a new memo?" else issue.status) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (choice != null) {
                        Text(if (choice == true) "The server version stays unchanged. A new memo will contain your local version."
                            else "Your local changes will be discarded. The server version, or its deletion, will be kept.")
                    } else {
                        Text(if (issue.deleted) "Local version · deletion requested" else "Local version")
                        SelectionContainer { Text(issue.desired.content, Modifier.padding(vertical = 8.dp)) }
                        if (issue.status == MemoSyncStatus.CONFLICT) {
                            Text("Server version")
                            SelectionContainer { Text(issue.server?.content ?: "Deleted or no longer readable", Modifier.padding(vertical = 8.dp)) }
                        } else if (issue.status == MemoSyncStatus.FAILED) {
                            Text(
                                if (issue.base == null) {
                                    "Create sync stopped. The local text is safe. Trying again is explicit and may duplicate the memo if an older server accepted the previous request under another ID."
                                } else {
                                    "Sync stopped for this memo. The local text is safe and can be selected and copied."
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (choice != null) TextButton(onClick = {
                    onResolve(issue.desired.name, choice == true); selected = null; choice = null
                }) { Text("Confirm") }
                else if (issue.status == MemoSyncStatus.CONFLICT) Column {
                    TextButton(onClick = { choice = true }) { Text("Keep local as new memo") }
                    TextButton(onClick = { choice = false }) { Text("Discard local / use server") }
                } else if (issue.status == MemoSyncStatus.FAILED) {
                    TextButton(onClick = {
                        onRetry(issue.desired.name)
                        selected = null
                    }) { Text("Try again") }
                }
            },
            dismissButton = { TextButton(onClick = { selected = null; choice = null }) { Text("Close") } },
        )
    }
}

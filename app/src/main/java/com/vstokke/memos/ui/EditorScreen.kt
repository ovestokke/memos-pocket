package com.vstokke.memos.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun EditorScreen(
    draft: EditorDraft,
    supportsReminder: Boolean,
    busy: Boolean,
    saving: Boolean,
    onChange: (EditorDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf(false) }
    var visibilityMenu by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box {
                TextButton(enabled = !busy, onClick = { visibilityMenu = true }) {
                    Icon(Icons.Outlined.Lock, contentDescription = null)
                    Text(
                        draft.visibility.lowercase().replaceFirstChar { it.uppercase() },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                DropdownMenu(expanded = visibilityMenu, onDismissRequest = { visibilityMenu = false }) {
                    val choices = listOf("PRIVATE", "PROTECTED", "PUBLIC") +
                        if (draft.base?.space != null) listOf("SPACE") else emptyList()
                    choices.forEach { visibility ->
                        DropdownMenuItem(
                            text = { Text(visibility.lowercase().replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                onChange(draft.copy(visibility = visibility))
                                visibilityMenu = false
                            },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !preview,
                    onClick = { preview = false },
                    label = { Text("Write") },
                )
                FilterChip(
                    selected = preview,
                    onClick = { preview = true },
                    label = { Text("Preview") },
                )
            }
        }

        if (preview) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
            ) {
                Box(Modifier.padding(16.dp)) {
                    MarkdownText(draft.content)
                }
            }
        } else {
            OutlinedTextField(
                value = draft.content,
                onValueChange = { onChange(draft.copy(content = it)) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                label = { Text("Memo") },
                supportingText = { Text("Markdown is supported") },
            )
        }

        if (supportsReminder) {
            ReminderPicker(draft.reminder, busy) {
                onChange(draft.copy(reminder = it))
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onSave,
                enabled = !busy && draft.content.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text(if (saving) "Saving…" else "Save changes")
            }
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("Memo draft", draft.content),
                )
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Text("Copy draft", modifier = Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
        }

        if (draft.visibility != "PRIVATE") {
            Text(
                when (draft.visibility) {
                    "PUBLIC" -> "Public memos may be readable without signing in."
                    "PROTECTED" -> "Visible to signed-in users."
                    else -> "Visible to active members of this space."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

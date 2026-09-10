package com.vstokke.memos.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EditorScreen(draft: EditorDraft, supportsReminder: Boolean, busy: Boolean,
    onChange: (EditorDraft) -> Unit, onSave: () -> Unit, onCancel: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var preview by remember { mutableStateOf(false) }
    var visibilityMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Box {
                TextButton(enabled = !busy, onClick = { visibilityMenu = true }) { Text(draft.visibility.lowercase().replaceFirstChar { it.uppercase() }) }
                DropdownMenu(visibilityMenu, { visibilityMenu = false }) {
                    (listOf("PRIVATE", "PROTECTED", "PUBLIC") + if (draft.base?.space != null) listOf("SPACE") else emptyList()).forEach { visibility ->
                        DropdownMenuItem(text = { Text(visibility) }, onClick = { onChange(draft.copy(visibility = visibility)); visibilityMenu = false })
                    }
                }
            }
            TextButton(onClick = { preview = !preview }) { Text(if (preview) "Write" else "Preview") }
        }
        if (preview) MarkdownText(draft.content) else OutlinedTextField(
            value = draft.content, onValueChange = { onChange(draft.copy(content = it)) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp), label = { Text("Memo · Markdown") },
        )
        if (supportsReminder) {
            ReminderPicker(draft.reminder, busy) { onChange(draft.copy(reminder = it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = !busy && draft.content.isNotBlank()) { Text(if (busy) "Saving…" else "Save") }
            TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
            TextButton(onClick = {
                context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                    android.content.ClipData.newPlainText("Memo draft", draft.content))
            }) { Text("Copy draft") }
        }
        if (draft.visibility != "PRIVATE") Text(when (draft.visibility) {
            "PUBLIC" -> "Public memos may be readable without signing in."
            "PROTECTED" -> "Visible to signed-in users."
            else -> "Visible to active members of this space."
        }, style = MaterialTheme.typography.bodySmall)
    }
}

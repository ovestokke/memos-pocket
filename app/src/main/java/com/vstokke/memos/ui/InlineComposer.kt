package com.vstokke.memos.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

@Composable
fun InlineComposer(draft: EditorDraft?, busy: Boolean, supportsReminder: Boolean,
    onChange: (EditorDraft) -> Unit, onSave: () -> Unit,
) {
    val value = draft ?: EditorDraft(null, "", "PRIVATE", "")
    var visibilityOpen by remember { mutableStateOf(false) }
    var reminderOpen by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Surface(shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(value.content, { onChange(value.copy(content = it)) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { field ->
                    Box {
                        if (value.content.isEmpty()) Text("Any thoughts…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        field()
                    }
                })
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (supportsReminder) TextButton(onClick = { reminderOpen = !reminderOpen }, enabled = !busy) {
                    Text(if (value.reminder.isBlank()) "Reminder" else "Reminder ✓")
                }
                Box {
                    TextButton(onClick = { visibilityOpen = true }, enabled = !busy) {
                        Text(value.visibility.lowercase().replaceFirstChar { it.uppercase() })
                    }
                    DropdownMenu(visibilityOpen, { visibilityOpen = false }) {
                        listOf("PRIVATE", "PROTECTED", "PUBLIC").forEach { visibility ->
                            DropdownMenuItem(text = { Text(visibility.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = { visibilityOpen = false; onChange(value.copy(visibility = visibility)) })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(enabled = !busy && value.content.isNotBlank(), onClick = {
                    focus.clearFocus()
                    onSave()
                }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("Save") }
            }
            if (supportsReminder && reminderOpen) ReminderPicker(value.reminder, busy) {
                onChange(value.copy(reminder = it))
                reminderOpen = false
            }
            if (!reminderOpen) com.vstokke.memos.domain.ReminderTime.parseServer(value.reminder)?.let {
                Text(formatLocalMemoTime(it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

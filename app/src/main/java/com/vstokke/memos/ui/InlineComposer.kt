package com.vstokke.memos.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue

@Composable
fun InlineComposer(
    draft: EditorDraft?,
    busy: Boolean,
    saving: Boolean,
    selectedSpace: String?,
    supportsReminder: Boolean,
    onChange: (EditorDraft) -> Unit,
    onSave: () -> Unit,
) {
    val value = draft ?: EditorDraft(
        base = null,
        content = "",
        visibility = if (selectedSpace == null) "PRIVATE" else "SPACE",
        reminder = "",
        space = selectedSpace,
    )
    var visibilityOpen by remember { mutableStateOf(false) }
    var reminderOpen by remember { mutableStateOf(false) }
    var editorValue by remember(value.base?.name) { mutableStateOf(TextFieldValue(value.content)) }
    val focus = LocalFocusManager.current

    LaunchedEffect(value.content) {
        if (editorValue.text != value.content) {
            editorValue = TextFieldValue(value.content)
        }
    }

    fun updateEditor(next: TextFieldValue) {
        val continued = MarkdownEditorOps.continueTask(editorValue, next)
        editorValue = continued
        onChange(value.copy(content = continued.text))
    }

    fun insertTask() {
        val inserted = MarkdownEditorOps.insertTask(editorValue)
        editorValue = inserted
        onChange(value.copy(content = inserted.text))
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            BasicTextField(
                value = editorValue,
                onValueChange = ::updateEditor,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(16.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { field ->
                    Box {
                        if (value.content.isEmpty()) {
                            Text(
                                "Any thoughts…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        field()
                    }
                },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = ::insertTask, enabled = !busy) {
                    Icon(Icons.Outlined.Checklist, contentDescription = "Insert task")
                }

                if (supportsReminder) {
                    IconButton(
                        onClick = { reminderOpen = !reminderOpen },
                        enabled = !busy,
                    ) {
                        Icon(
                            Icons.Outlined.NotificationsNone,
                            contentDescription = if (value.reminder.isBlank()) "Add reminder" else "Change reminder",
                            tint = if (value.reminder.isBlank()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }

                Box {
                    TextButton(onClick = { visibilityOpen = true }, enabled = !busy) {
                        Icon(Icons.Outlined.Lock, contentDescription = null)
                        Text(
                            value.visibility.lowercase().replaceFirstChar { it.uppercase() },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    DropdownMenu(expanded = visibilityOpen, onDismissRequest = { visibilityOpen = false }) {
                        val choices = listOf("PRIVATE", "PROTECTED", "PUBLIC") +
                            if (value.space == null) emptyList() else listOf("SPACE")
                        choices.forEach { visibility ->
                            DropdownMenuItem(
                                text = { Text(visibility.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    visibilityOpen = false
                                    onChange(value.copy(visibility = visibility))
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                Button(
                    enabled = !busy && value.content.isNotBlank(),
                    onClick = {
                        focus.clearFocus()
                        onSave()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(if (saving) "Saving…" else "Save")
                }
            }

            if (supportsReminder && reminderOpen) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.padding(16.dp)) {
                    ReminderPicker(value.reminder, busy) {
                        onChange(value.copy(reminder = it))
                        reminderOpen = false
                    }
                }
            }

            if (!reminderOpen) {
                com.vstokke.memos.domain.ReminderTime.parseServer(value.reminder)?.let {
                    Text(
                        formatLocalMemoTime(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

package com.vstokke.memos.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vstokke.memos.domain.ReminderTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderPicker(value: String, busy: Boolean, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val selected = ReminderTime.parseServer(value)
    val initial = (selected ?: Instant.now().plusSeconds(300)).atZone(zone)
    var error by remember { mutableStateOf<String?>(null) }
    var quickMenu by remember { mutableStateOf(false) }
    var dateOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf(initial.toLocalDate()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Reminder",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            enabled = !busy,
            onClick = { dateOpen = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
            Text(
                selected?.let { formatLocalMemoTime(it, zone) } ?: "Choose date and time",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(enabled = !busy, onClick = { quickMenu = true }) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null)
                    Text("Quick time", modifier = Modifier.padding(start = 8.dp))
                }
                DropdownMenu(expanded = quickMenu, onDismissRequest = { quickMenu = false }) {
                    ReminderTime.quickHours.forEach { hour ->
                        val due = ReminderTime.nextLocalHour(hour, Instant.now(), zone)
                        val day = if (due.atZone(zone).toLocalDate() == LocalDate.now(zone)) {
                            "Today"
                        } else {
                            "Tomorrow"
                        }
                        DropdownMenuItem(
                            text = { Text("$day · %02d:00".format(hour)) },
                            enabled = !busy,
                            onClick = {
                                quickMenu = false
                                error = null
                                onChange(ReminderTime.toServer(ReminderTime.nextLocalHour(hour, Instant.now(), zone)))
                            },
                        )
                    }
                }
            }
            if (value.isNotBlank()) {
                TextButton(enabled = !busy, onClick = {
                    error = null
                    onChange("")
                }) {
                    Text("Remove")
                }
            }
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (dateOpen) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = initial.toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { dateOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = dateState.selectedDateMillis ?: return@TextButton
                    pendingDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    dateOpen = false
                    timeOpen = true
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { dateOpen = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (timeOpen) {
        val timeState = rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { timeOpen = false },
            title = { Text("Choose time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val due = ReminderTime.exactLocal(
                        pendingDate,
                        LocalTime.of(timeState.hour, timeState.minute),
                        Instant.now(),
                        zone,
                    )
                    if (due == null) {
                        error = "Choose a valid time in the future."
                    } else {
                        error = null
                        onChange(ReminderTime.toServer(due))
                    }
                    timeOpen = false
                }) { Text("Set reminder") }
            },
            dismissButton = {
                TextButton(onClick = { timeOpen = false }) { Text("Cancel") }
            },
        )
    }
}

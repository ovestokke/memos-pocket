package com.vstokke.memos.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vstokke.memos.domain.ReminderTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun ReminderPicker(value: String, busy: Boolean, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val currentOnChange by rememberUpdatedState(onChange)
    val currentBusy by rememberUpdatedState(busy)
    val zone = ZoneId.systemDefault()
    val selected = ReminderTime.parseServer(value)
    var error by remember { mutableStateOf<String?>(null) }
    var quickMenu by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf<DatePickerDialog?>(null) }
    var timeDialog by remember { mutableStateOf<TimePickerDialog?>(null) }
    DisposableEffect(Unit) {
        onDispose { dateDialog?.dismiss(); timeDialog?.dismiss() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Reminder", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(enabled = !busy, onClick = {
            val initial = (selected ?: Instant.now().plusSeconds(300)).atZone(zone)
            dateDialog = DatePickerDialog(context, { _, year, month, day ->
                timeDialog = TimePickerDialog(context, { _, hour, minute ->
                    if (!currentBusy) {
                        val due = ReminderTime.exactLocal(LocalDate.of(year, month + 1, day),
                            LocalTime.of(hour, minute), Instant.now(), zone)
                        if (due == null) error = "Choose a valid time in the future."
                        else { error = null; currentOnChange(ReminderTime.toServer(due)) }
                    }
                }, initial.hour, initial.minute, DateFormat.is24HourFormat(context)).also { it.show() }
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).also { it.show() }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.let { formatLocalMemoTime(it, zone) }
                ?: "Choose date and time")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Box {
                TextButton(enabled = !busy, onClick = { quickMenu = true }) { Text("Quick time") }
                DropdownMenu(expanded = quickMenu, onDismissRequest = { quickMenu = false }) {
                    ReminderTime.quickHours.forEach { hour ->
                        val due = ReminderTime.nextLocalHour(hour, Instant.now(), zone)
                        val day = if (due.atZone(zone).toLocalDate() == LocalDate.now(zone)) "Today" else "Tomorrow"
                        DropdownMenuItem(text = { Text("$day · %02d:00".format(hour)) }, enabled = !busy,
                            onClick = {
                                quickMenu = false
                                error = null
                                onChange(ReminderTime.toServer(ReminderTime.nextLocalHour(hour, Instant.now(), zone)))
                            })
                    }
                }
            }
            if (value.isNotBlank()) TextButton(enabled = !busy, onClick = { error = null; onChange("") }) {
                Text("Remove reminder")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

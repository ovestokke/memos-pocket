package com.vstokke.memos.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(state: MainUiState, onConnect: (String, String) -> Unit) {
    var server by rememberSaveable { mutableStateOf("https://memos.vstokke.com") }
    // PAT never enters saved instance state or the ViewModel.
    var token by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Memos Pocket", style = MaterialTheme.typography.titleLarge)
            Text("Connect to your Memos server", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(server, { server = it }, label = { Text("Server address") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
            OutlinedTextField(token, { token = it }, label = { Text("Personal access token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { onConnect(server, token) }, enabled = !state.busy && token.isNotBlank()) { Text(if (state.busy) "Connecting…" else "Connect") }
            Text("Unofficial third-party client for Memos. Your token is stored encrypted on this device.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

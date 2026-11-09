package app.crew.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.crew.Connection
import app.crew.CrewViewModel
import app.crew.UiState
import app.crew.data.NetworkChoice

@Composable
fun SettingsScreen(state: UiState, viewModel: CrewViewModel, padding: PaddingValues) {
    val saved = state.settings
    var lan by remember(saved) { mutableStateOf(saved.lanUrl) }
    var tailnet by remember(saved) { mutableStateOf(saved.tailnetUrl) }
    var token by remember(saved) { mutableStateOf(saved.token) }
    var network by remember(saved) { mutableStateOf(saved.network) }
    var showToken by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("Restart the Crew backend?") },
            text = {
                Text(
                    "Stops the server and every agent process, then systemd starts it again (about 10 to 15 seconds). " +
                        "Running tasks end with an error; your chats stay. The browser keeps running.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestart = false
                    viewModel.restartBackend()
                }) { Text("Restart") }
            },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Cancel") } },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (val c = state.connection) {
                        is Connection.Online -> "Connected over ${c.route}. Crew ${c.version}" +
                            (state.engines.takeIf { it.isNotEmpty() }?.joinToString(prefix = ". Agents: ") { it.name } ?: "")
                        is Connection.Offline -> "Offline: ${c.reason}"
                        Connection.Connecting -> "Connecting…"
                        Connection.NotSetUp -> "Enter the server address and token to connect."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = lan, onValueChange = { lan = it },
                    label = { Text("Home network address") },
                    placeholder = { Text("http://192.168.1.10:8800") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = tailnet, onValueChange = { tailnet = it },
                    label = { Text("Tailscale address") },
                    placeholder = { Text("https://machine.tailnet.ts.net:8800") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") } },
                )
                Text("Use", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(NetworkChoice.Auto to "Auto", NetworkChoice.Lan to "Home", NetworkChoice.Tailnet to "Tailscale").forEach { (choice, label) ->
                        FilterChip(selected = network == choice, onClick = { network = choice }, label = { Text(label) })
                    }
                }
                Button(
                    onClick = { viewModel.saveSettings(saved.copy(lanUrl = lan, tailnetUrl = tailnet, token = token, network = network)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save and connect") }
                Text(
                    "On the server, ./master.sh status shows its addresses and ./master.sh token shows the token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard {
                Text("Emergency", style = MaterialTheme.typography.titleMedium)
                Text(
                    "If the agents are stuck, restart the Crew backend. Only use this when Stop does not help.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { confirmRestart = true },
                    enabled = state.connection is Connection.Online && !state.restarting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(if (state.restarting) "Restarting…" else "Restart Crew backend") }
            }
        }
    }
}

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
import androidx.compose.runtime.LaunchedEffect
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
import app.crew.data.ConnectionSettings

@Composable
fun SettingsScreen(state: UiState, viewModel: CrewViewModel, padding: PaddingValues) {
    val saved = state.settings
    var addresses by remember(saved) { mutableStateOf(saved.addresses.joinToString("\n")) }
    var token by remember(saved) { mutableStateOf(saved.token) }
    var showToken by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    val online = state.connection is Connection.Online
    LaunchedEffect(online) { if (online) viewModel.loadProfiles() }

    state.pendingConnect?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.answerConnectLink(false) },
            title = { Text("Connect to this Crew server?") },
            text = {
                Text(
                    "This replaces the app's server settings with:\n\n" + pending.addresses.joinToString("\n") +
                        "\n\nOnly say yes for a code from your own server (./master.sh token).",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.answerConnectLink(true) }) { Text("Connect") } },
            dismissButton = { TextButton(onClick = { viewModel.answerConnectLink(false) }) { Text("Cancel") } },
        )
    }

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
                        is Connection.Online -> "Connected over ${c.route}" + (if (c.secure) " (encrypted)" else "") + ". Crew ${c.version}" +
                            (state.engines.takeIf { it.isNotEmpty() }?.joinToString(prefix = ". Agents: ") { it.name } ?: "")
                        is Connection.Offline -> "Offline: ${c.reason}"
                        Connection.Connecting -> "Connecting…"
                        Connection.NotSetUp -> if (saved.addresses.isNotEmpty() && saved.token.isBlank())
                            "Set this phone up with your security key (Security, below)."
                        else "On the server, run ./master.sh token and open its code on this phone, or enter the address and token."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = addresses, onValueChange = { addresses = it },
                    label = { Text("Server addresses") },
                    placeholder = { Text("https://192.168.1.10:8800\nhttps://machine.tailnet.ts.net:8800") },
                    supportingText = { Text("One per line, tried in order: home network, Tailscale, your domain.") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (!saved.paired) OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") } },
                )
                Text(
                    if (saved.pin.isBlank()) "Server certificate: not known yet. It comes with the connect code, or over Tailscale."
                    else "Server certificate: pinned (${saved.pin.removePrefix("sha256/").take(10)}…). The app refuses any other.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { viewModel.saveSettings(saved.copy(addresses = ConnectionSettings.parseAddresses(addresses), token = token)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save and connect") }
                Text(
                    "On the server, ./master.sh token shows a code that fills all of this in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { SecurityCard(state, viewModel) }
        item { SignInsCard(state, viewModel) }
        item { ProfilesCard(state, viewModel) }
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

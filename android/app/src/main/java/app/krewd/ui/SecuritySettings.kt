package app.krewd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.krewd.Connection
import app.krewd.KrewdViewModel
import app.krewd.UiState
import app.krewd.data.Device

/** Settings > Security: set this phone up with the owner's security key, and see or remove the owner's phones. */
@Composable
fun SecurityCard(state: UiState, viewModel: KrewdViewModel) {
    val settings = state.settings
    val online = state.connection is Connection.Online
    var name by remember { mutableStateOf(android.os.Build.MODEL) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Device?>(null) }
    LaunchedEffect(online, settings.paired) { if (online && settings.paired) viewModel.loadSecurity() }

    state.securityStep?.let { step ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Security key") },
            text = { Text(step) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { viewModel.cancelKeyStep() }) { Text("Cancel") } },
        )
    }
    removing?.let { device ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${device.name}?") },
            text = { Text(if (device.current) "This phone stops working with Krewd until you set it up again with your security key." else "That phone can no longer use Krewd. Tap your security key to confirm.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    viewModel.removeDevice(device) { error = it }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }

    SectionCard {
        Text("Security", style = MaterialTheme.typography.titleMedium)
        if (!settings.paired) {
            Text(
                if (settings.token.isNotBlank())
                    "Set this phone up with your security key. Krewd then trusts only your phones: this phone proves itself with its own key, " +
                        "your fingerprint confirms sign-ins and gateway keys, and adding a phone needs a tap of the key. The setup token stops working."
                else
                    "Set this phone up with your security key: plug it into the phone's USB-C port (or hold it to the back, on phones with NFC) and touch it when asked.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("This phone's name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            )
            Button(
                onClick = { error = null; viewModel.pairPhone(name) { error = it } },
                enabled = settings.addresses.isNotEmpty() && state.securityStep == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Set up with security key") }
        } else {
            Text(
                "This phone signs in with its own key. Sign-ins and gateway keys need your fingerprint; adding or removing a phone needs your security key.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.devices?.forEach { device ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name + if (device.current) " (this phone)" else "", style = MaterialTheme.typography.bodyLarge)
                        Text("Added ${localTime(device.createdAt)}" + (device.lastSeen?.let { ", last seen ${localTime(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { removing = device }, enabled = online && state.securityStep == null) { Text("Remove") }
                }
            }
            Text(
                "New phone: install Krewd, open the code from ./master.sh token on it (or enter the address), then tap your security key. " +
                    "Lost the phone and the key: ./master.sh security reset on the server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** An ISO time from the server, in this phone's time zone: "14 Nov, 01:29". */
private fun localTime(iso: String): String = runCatching {
    java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))
}.getOrDefault(iso.take(10))

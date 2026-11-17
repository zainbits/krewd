package app.krewd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.krewd.OwnerMethod
import app.krewd.UiState
import app.krewd.data.Device
import app.krewd.data.OwnerKey

/**
 * Settings > Security: set this phone up with an owner key (the FIDO security key or a phone passkey), see or
 * remove the owner's phones, and add or remove owner keys.
 */
@Composable
fun SecurityCard(state: UiState, viewModel: KrewdViewModel) {
    val settings = state.settings
    val online = state.connection is Connection.Online
    val busy = state.securityStep != null
    var name by remember { mutableStateOf(android.os.Build.MODEL) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Device?>(null) }
    var removingKey by remember { mutableStateOf<OwnerKey?>(null) }
    var choose by remember { mutableStateOf<((OwnerMethod) -> Unit)?>(null) }
    LaunchedEffect(online, settings.paired) { if (online && settings.paired) viewModel.loadSecurity() }

    val keys = state.ownerKeys.orEmpty()
    val hasSecurityKey = keys.any { !it.passkey }
    val hasPasskey = keys.any { it.passkey } && state.passkeyRpId != null
    /** Runs [action] with the owner key to confirm with: asks only when both kinds are registered. */
    fun confirmWith(action: (OwnerMethod) -> Unit) {
        error = null
        when {
            hasSecurityKey && hasPasskey -> choose = action
            hasPasskey -> action(OwnerMethod.PASSKEY)
            else -> action(OwnerMethod.SECURITY_KEY)
        }
    }

    state.securityStep?.let { step ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Owner key") },
            text = { Text(step) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { viewModel.cancelKeyStep() }) { Text("Cancel") } },
        )
    }
    choose?.let { action ->
        AlertDialog(
            onDismissRequest = { choose = null },
            title = { Text("Confirm with") },
            text = { Text("Use your security key or your phone passkey.") },
            confirmButton = { TextButton(onClick = { choose = null; action(OwnerMethod.PASSKEY) }) { Text("Passkey") } },
            dismissButton = { TextButton(onClick = { choose = null; action(OwnerMethod.SECURITY_KEY) }) { Text("Security key") } },
        )
    }
    removing?.let { device ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${device.name}?") },
            text = { Text(if (device.current) "This phone stops working with Krewd until you set it up again with an owner key." else "That phone can no longer use Krewd. Confirm with an owner key.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    confirmWith { m -> viewModel.removeDevice(device, m) { error = it } }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    removingKey?.let { key ->
        val phones = state.devices.orEmpty().filter { it.key == key.id }
        AlertDialog(
            onDismissRequest = { removingKey = null },
            title = { Text("Remove ${key.name}?") },
            text = {
                Text(
                    (if (key.passkey) "Krewd stops accepting this passkey. Delete it from your password manager too if you like. "
                     else "Krewd stops accepting this security key. ") +
                        (if (phones.isEmpty()) "No phone was added with it."
                         else "Phones added with it are removed too: " + phones.joinToString { it.name + if (it.current) " (this phone)" else "" } + ".")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removingKey = null
                    confirmWith { m -> viewModel.removeOwnerKey(key, m) { error = it } }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removingKey = null }) { Text("Cancel") } },
        )
    }

    SectionCard {
        Text("Security", style = MaterialTheme.typography.titleMedium)
        if (!settings.paired) {
            Text(
                if (settings.token.isNotBlank())
                    "Set this phone up with an owner key: your FIDO security key, or a passkey kept by this phone's password manager. " +
                        "Krewd then trusts only your phones: this phone proves itself with its own key, your fingerprint confirms sign-ins " +
                        "and gateway keys, and adding a phone needs an owner key. The setup token stops working."
                else
                    "Set this phone up with an owner key: plug your security key into the USB-C port (or hold it to the back), or use your Krewd passkey " +
                        "(synced to this phone through your password manager).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("This phone's name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            )
            Button(
                onClick = { error = null; viewModel.pairPhone(name, OwnerMethod.SECURITY_KEY) { error = it } },
                enabled = settings.addresses.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Set up with security key") }
            OutlinedButton(
                onClick = { error = null; viewModel.pairPhone(name, OwnerMethod.PASSKEY) { error = it } },
                enabled = settings.addresses.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Set up with passkey") }
        } else {
            Text(
                "This phone signs in with its own key. Sign-ins and gateway keys need your fingerprint; adding or removing a phone or an owner key needs an owner key.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Phones", style = MaterialTheme.typography.titleSmall)
            state.devices?.forEach { device ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name + if (device.current) " (this phone)" else "", style = MaterialTheme.typography.bodyLarge)
                        val via = keys.firstOrNull { it.id == device.key }?.name
                        Text("Added ${localTime(device.createdAt)}" + (via?.let { " with $it" } ?: "") +
                                (device.lastSeen?.let { ", last seen ${localTime(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { removing = device }, enabled = online && !busy) { Text("Remove") }
                }
            }
            Text("Owner keys", style = MaterialTheme.typography.titleSmall)
            keys.forEach { key ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(key.name, style = MaterialTheme.typography.bodyLarge)
                        Text(if (key.passkey) "Phone passkey" else "FIDO security key",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { removingKey = key }, enabled = online && !busy && keys.size > 1) { Text("Remove") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.passkeyRpId != null) {
                    OutlinedButton(
                        onClick = { confirmWith { m -> viewModel.addOwnerKey("passkey", m) { error = it } } },
                        enabled = online && !busy && keys.isNotEmpty(), modifier = Modifier.weight(1f),
                    ) { Text("Add passkey") }
                }
                if (!hasSecurityKey && keys.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { error = null; viewModel.addOwnerKey("u2f", OwnerMethod.PASSKEY) { error = it } },
                        enabled = online && !busy, modifier = Modifier.weight(1f),
                    ) { Text("Add security key") }
                }
            }
            if (state.ownerKeys != null && state.passkeyRpId == null) {
                Text("Passkeys are off on the server (KREWD_PASSKEY_RP_ID in krewd.env).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "New phone: install Krewd, open the code from ./master.sh token on it (or enter the address), then use your security key or passkey. " +
                    "Lost every owner key: ./master.sh security reset on the server.",
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

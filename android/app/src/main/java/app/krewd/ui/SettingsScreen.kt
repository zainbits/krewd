package app.krewd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.graphics.luminance
import io.github.zainbits.cohesion.GlassStyle
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.krewd.Connection
import app.krewd.KrewdViewModel
import app.krewd.UiState
import app.krewd.findConnectLink
import app.krewd.data.ConnectionSettings

@Composable
fun SettingsScreen(state: UiState, viewModel: KrewdViewModel, padding: PaddingValues, listState: androidx.compose.foundation.lazy.LazyListState) {
    val saved = state.settings
    var addresses by remember(saved) { mutableStateOf(saved.addresses.joinToString("\n")) }
    var token by remember(saved) { mutableStateOf(saved.token) }
    var showToken by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var locationSheet by remember { mutableStateOf(false) }
    var storageSheet by remember { mutableStateOf(false) }
    val online = state.connection is Connection.Online
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(online) { if (online) viewModel.loadProfiles() }

    state.pendingConnect?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.answerConnectLink(false) },
            title = { Text("Connect to this Krewd server?") },
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
            title = { Text("Restart the Krewd backend?") },
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

    if (locationSheet) LocationSheet(viewModel) { locationSheet = false }
    if (storageSheet) StorageSheet(viewModel) { storageSheet = false }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (val c = state.connection) {
                        is Connection.Online -> "Connected over ${c.route}" + (if (c.secure) " (encrypted)" else "") + ". Krewd ${c.version}" +
                            (state.engines.takeIf { it.isNotEmpty() }?.joinToString(prefix = ". Agents: ") { it.name } ?: "")
                        is Connection.Offline -> "Offline: ${c.reason}"
                        Connection.Connecting -> "Connecting…"
                        Connection.NotSetUp -> if (saved.addresses.isNotEmpty() && saved.token.isBlank())
                            "Set this phone up with your security key (Security, below)."
                        else "On the server, run ./master.sh token. Scan its QR code from another device, or copy its krewd://connect link and tap Paste connect code."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The connect code on this same phone: a QR app or terminal copies the link instead of opening it.
                val pasteCode = { viewModel.pasteConnectCode(clipboard.getText()?.text) }
                if (online) OutlinedButton(onClick = { pasteCode() }, modifier = Modifier.fillMaxWidth()) { Text("Paste connect code") }
                else Button(onClick = { pasteCode() }, modifier = Modifier.fillMaxWidth()) { Text("Paste connect code") }
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
                OutlinedButton(
                    onClick = {
                        // A connect link pasted into a field does what Paste connect code does.
                        if (findConnectLink(addresses + "\n" + token) != null) viewModel.pasteConnectCode(addresses + "\n" + token)
                        else viewModel.saveSettings(saved.copy(addresses = ConnectionSettings.parseAddresses(addresses), token = token))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save and connect") }
                Text(
                    "On the server, ./master.sh token shows a code that fills all of this in: scan its QR code, or copy its link and paste it here.",
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
                Text("Chat", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Swipe to note", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Swipe part of a reply to the right to leave a margin note on it. Tapping a part, or selecting words and picking Note, works either way.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.swipeToNote, onCheckedChange = viewModel::setSwipeToNote)
                }
                val glass = LocalKrewdGlass.current
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Liquid glass", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (glass.throttled) {
                                "Off while battery saver is on (or the phone is hot), to save power."
                            } else {
                                "See-through title bar, composer and nav bar that stretch when pulled. Off: plain panels. Battery saver turns it off by itself."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = glass.on,
                        onCheckedChange = viewModel::setGlassEnabled,
                        enabled = !glass.throttled,
                    )
                }
                GlassSlider(
                    title = "Glass clarity",
                    start = "Clear",
                    end = "Tinted",
                    value = state.glassClarity,
                    enabled = glass.on,
                    onValue = { v, save -> viewModel.setGlassClarity(v, save) },
                )
                val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                val tintDefault = if (darkTheme) GlassStyle.DEFAULT_TINT_DARK else GlassStyle.DEFAULT_TINT_LIGHT
                GlassSlider(
                    title = if (darkTheme) "Dark tint" else "White tint",
                    start = "None",
                    end = "Strong",
                    value = state.glassTint ?: tintDefault,
                    enabled = glass.on,
                    onValue = { v, save -> viewModel.setGlassTint(v, save) },
                    onReset = if (state.glassTint != null) ({ viewModel.setGlassTint(null, save = true) }) else null,
                )
                GlassSlider(
                    title = "Edge light",
                    start = "Off",
                    end = "Bright",
                    value = state.glassEdgeLight ?: GlassStyle.forClarity(state.glassClarity, darkTheme).rim.coerceIn(0f, 1f),
                    enabled = glass.on,
                    onValue = { v, save -> viewModel.setGlassEdgeLight(v, save) },
                    onReset = if (state.glassEdgeLight != null) ({ viewModel.setGlassEdgeLight(null, save = true) }) else null,
                )
            }
        }
        item {
            SectionCard {
                Text("Browser location", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The location websites in the agents' Brave see. Set it from this phone's GPS, a favorite or by hand; it changes only when you apply one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { locationSheet = true }, enabled = online, modifier = Modifier.fillMaxWidth()) { Text("Browser location") }
            }
        }
        item {
            SectionCard {
                Text("Storage", style = MaterialTheme.typography.titleMedium)
                Text(
                    "See which chats take the most space on the server, free what no chat needs, and trim or delete big chats.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { storageSheet = true }, enabled = online, modifier = Modifier.fillMaxWidth()) { Text("Storage") }
            }
        }
        item {
            SectionCard {
                Text("Emergency", style = MaterialTheme.typography.titleMedium)
                Text(
                    "If the agents are stuck, restart the Krewd backend. Only use this when Stop does not help.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { confirmRestart = true },
                    enabled = state.connection is Connection.Online && !state.restarting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(if (state.restarting) "Restarting…" else "Restart Krewd backend") }
            }
        }
    }
}

/**
 * One glass setting: a slider in whole steps of 1 (0 to 100) and a box to type the exact number, so the two
 * always agree. [onValue] gets save = false while dragging and true when the finger lifts or a number is typed.
 */
@Composable
private fun GlassSlider(
    title: String,
    start: String,
    end: String,
    value: Float,
    enabled: Boolean,
    onValue: (Float, Boolean) -> Unit,
    onReset: (() -> Unit)? = null,
) {
    var latest by remember { mutableStateOf(value) }
    latest = value
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
                modifier = Modifier.weight(1f),
            )
            if (onReset != null) TextButton(onClick = onReset, enabled = enabled) { Text("Default") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(start, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.Slider(
                value = value,
                onValueChange = { onValue(Math.round(it * 100f) / 100f, false) },
                onValueChangeFinished = { onValue(latest, true) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Text(end, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NumberField(value = value, enabled = enabled, label = "$title, 0 to 100", onChange = { onValue(it, true) })
        }
    }
}

/** A value from 0 to 1 as a whole number from 0 to 100, for setting an exact value. */
@Composable
private fun NumberField(value: Float, enabled: Boolean, label: String, onChange: (Float) -> Unit) {
    val shown = Math.round(value * 100f).toString()
    var text by remember { mutableStateOf(shown) }
    var focused by remember { mutableStateOf(false) }
    // Follow the slider while the field is not being typed in.
    LaunchedEffect(shown, focused) { if (!focused) text = shown }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            val digits = typed.filter(Char::isDigit).take(3)
            text = digits
            digits.toIntOrNull()?.takeIf { it in 0..100 }?.let { onChange(it / 100f) }
        },
        enabled = enabled,
        singleLine = true,
        isError = text.toIntOrNull()?.let { it !in 0..100 } ?: false,
        textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier
            .width(72.dp)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label },
    )
}

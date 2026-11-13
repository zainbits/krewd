package app.crew.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.crew.Connection
import app.crew.CrewViewModel
import app.crew.UiState
import app.crew.data.AgentLogin
import app.crew.data.ProfileInfo
import app.crew.data.ProfilePreset
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Settings > Profiles: the gateways the agents can run on instead of their own sign-in. One per gateway; the
 * models come from the gateway, so they are limited there (an OpenRouter key's guardrail), never here.
 */
@Composable
fun ProfilesCard(state: UiState, viewModel: CrewViewModel) {
    var editing by remember { mutableStateOf<ProfileInfo?>(null) }
    var adding by remember { mutableStateOf(false) }
    val online = state.connection is Connection.Online

    SectionCard {
        Text("Profiles", style = MaterialTheme.typography.titleMedium)
        Text(
            "Gateways your agents can run on instead of their sign-in account (pick one per agent above). The models " +
                "come from the gateway, so limit them there (for OpenRouter, the key's guardrail).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.profiles.profiles.forEach { profile ->
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable(enabled = online && state.profiles.managed) { editing = profile }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            listOfNotNull(profile.openai?.let { "Codex" }, profile.anthropic?.let { "Claude Code" }).joinToString(" and ").let { "For $it" },
                            if (!profile.keySet) "no key" else profile.keyHint?.let { "key …$it" } ?: "key set",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (profile.keySet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                if (state.profiles.managed) Text("Edit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (state.profiles.managed) {
            OutlinedButton(onClick = { adding = true }, enabled = online, modifier = Modifier.fillMaxWidth()) { Text("Add profile") }
        } else if (online) {
            Text("Profiles are not managed from the app on this server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (adding || editing != null) {
        ProfileEditorSheet(
            existing = editing,
            presets = state.profiles.presets,
            secure = (state.connection as? Connection.Online)?.secure == true,
            viewModel = viewModel,
            onDismiss = { adding = false; editing = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditorSheet(
    existing: ProfileInfo?,
    presets: List<ProfilePreset>,
    secure: Boolean,
    viewModel: CrewViewModel,
    onDismiss: () -> Unit,
) {
    var preset by remember { mutableStateOf(if (existing == null) presets.firstOrNull() else null) }
    var name by remember { mutableStateOf(existing?.name ?: preset?.name ?: "") }
    var openai by remember { mutableStateOf(existing?.openai ?: preset?.openai ?: "") }
    var anthropic by remember { mutableStateOf(existing?.anthropic ?: preset?.anthropic ?: "") }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (existing == null) "Add a profile" else existing.name, style = MaterialTheme.typography.titleLarge)
            if (existing == null) {
                SheetLabel("Start from")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (presets.map { it to it.name } + (null to "Custom")).forEach { (choice, label) ->
                        FilterChip(selected = preset == choice, onClick = {
                            preset = choice
                            name = choice?.name ?: ""
                            openai = choice?.openai ?: ""
                            anthropic = choice?.anthropic ?: ""
                        }, label = { Text(label) })
                    }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            OutlinedTextField(
                openai, { openai = it }, label = { Text("OpenAI address, for Codex") }, singleLine = true,
                placeholder = { Text("https://gateway.example/v1") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                anthropic, { anthropic = it }, label = { Text("Anthropic address, for Claude Code") }, singleLine = true,
                placeholder = { Text("https://gateway.example") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                key, { key = it }, label = { Text("API key") }, singleLine = true,
                supportingText = {
                    Text(
                        (if (existing?.keySet == true) "Set${existing.keyHint?.let { " (…$it)" } ?: ""}. Leave empty to keep it. " else "") +
                            "Sent only over encrypted HTTPS, stored on the server, never shown again.",
                    )
                },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            )
            if (key.isNotBlank() && !secure) {
                Text("Not connected over encrypted HTTPS yet: keys never go over a plain connection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    saving = true
                    error = null
                    val fields = JSONObject().put("name", name.trim()).put("openai", openai.trim()).put("anthropic", anthropic.trim())
                    if (existing == null && preset != null) fields.put("preset", preset!!.id)
                    viewModel.saveProfile(existing?.id, fields, key.takeIf { it.isNotBlank() }) { problem ->
                        saving = false
                        if (problem == null) {
                            viewModel.toast(if (existing == null) "Added ${name.trim()}" else "Saved ${name.trim()}")
                            onDismiss()
                        } else {
                            error = problem
                        }
                    }
                },
                enabled = !saving && name.isNotBlank() && (openai.isNotBlank() || anthropic.isNotBlank()) && (existing != null || key.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  Checking the gateway…")
                } else {
                    Text(if (existing == null) "Add profile" else "Save")
                }
            }
            if (existing != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete profile") }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = { Text("Its key is removed from the server too. Chats and routines still on it must be archived or switched first.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteProfile(existing.id) { problem ->
                        if (problem == null) {
                            viewModel.toast("Deleted ${existing.name}")
                            onDismiss()
                        } else {
                            error = problem
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Settings > Agents: each agent's sign-in account (sign in and out here) and the profile it runs on. The sign-in
 * account is one choice among the profiles; signing in never changes which one is picked. Chats, routines and new
 * tasks just name the agent: they follow it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignInsCard(state: UiState, viewModel: CrewViewModel) {
    var signingIn by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf<AgentLogin?>(null) }
    val online = state.connection is Connection.Online
    if (state.logins.isEmpty()) return

    SectionCard {
        Text("Agents", style = MaterialTheme.typography.titleMedium)
        Text(
            "What each agent runs on: its sign-in account or a profile. Every chat and routine with that agent follows the pick.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.logins.forEach { login ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(login.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (login.loggedIn) {
                            true -> "Signed in" + (login.account?.let { " · $it" } ?: "")
                            false -> "Not signed in" + (login.error?.let { " ($it)" } ?: "")
                            null -> "Checking…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (login.loggedIn == false && login.profile == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    login.flow?.active == true -> TextButton(onClick = { signingIn = login.engine }, enabled = online) { Text("Continue") }
                    login.loggedIn == true -> TextButton(onClick = { confirmSignOut = login }, enabled = online) { Text("Sign out") }
                    else -> Button(onClick = {
                        signingIn = login.engine
                        viewModel.startLogin(login.engine) { problem -> if (problem != null) viewModel.toast(problem) }
                    }, enabled = online) { Text("Sign in") }
                }
            }
            if (login.profileChoices.isNotEmpty()) {
                Text("Runs on", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf<String?>(null) + login.profileChoices).forEach { choice ->
                        FilterChip(
                            selected = login.profile == choice,
                            onClick = { if (login.profile != choice) viewModel.setAgentProfile(login.engine, choice) },
                            enabled = online,
                            label = { Text(choice?.let { id -> state.profiles.profiles.firstOrNull { it.id == id }?.name ?: id } ?: "Sign-in account") },
                        )
                    }
                }
            }
        }
    }

    signingIn?.let { engine ->
        state.logins.firstOrNull { it.engine == engine }?.let { login ->
            SignInSheet(login, viewModel, onDismiss = { signingIn = null })
        }
    }
    confirmSignOut?.let { login ->
        AlertDialog(
            onDismissRequest = { confirmSignOut = null },
            title = { Text("Sign ${login.name} out?") },
            text = { Text(if (login.profile == null) "${login.name} runs on its sign-in account, so its chats cannot run until you sign in again or pick a profile." else "${login.name} runs on a profile, so nothing changes for its chats.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = null; viewModel.logout(login.engine) }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignInSheet(login: AgentLogin, viewModel: CrewViewModel, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val flow = login.flow
    var code by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // The sign-in finishes on the server (Codex by itself, Claude Code after the pasted code): follow it.
    LaunchedEffect(login.engine) {
        while (true) {
            delay(2_000)
            viewModel.refreshLogins()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sign in to ${login.name}", style = MaterialTheme.typography.titleLarge)
            when (flow?.state) {
                null, "starting" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  Starting the sign-in on the server…")
                }
                "waiting", "finishing" -> {
                    Text("1. Open the sign-in page and sign in with your subscription account.", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { flow.url?.let(uri::openUri) }, enabled = flow.url != null, modifier = Modifier.fillMaxWidth()) { Text("Open sign-in page") }
                    if (flow.code != null) {
                        Text("2. Enter this code on that page:", style = MaterialTheme.typography.bodyLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(flow.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 30.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { clipboard.setText(AnnotatedString(flow.code)); viewModel.toast("Code copied") }) { Text("Copy") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("  Crew finishes by itself once you have entered it (code valid ${flow.expiresIn / 60} more min).", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (flow.paste) {
                        Text("2. Copy the code the page shows at the end, and paste it here:", style = MaterialTheme.typography.bodyLarge)
                        OutlinedTextField(
                            code, { code = it }, label = { Text("Code from the page") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                        )
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        Button(
                            onClick = {
                                sending = true
                                error = null
                                viewModel.submitLoginCode(login.engine, code) { problem -> sending = false; error = problem }
                            },
                            enabled = !sending && flow.state == "waiting" && code.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (sending || flow.state == "finishing") "Finishing…" else "Finish sign-in") }
                    }
                    TextButton(onClick = { viewModel.cancelLogin(login.engine); onDismiss() }) { Text("Cancel sign-in") }
                }
                "done" -> {
                    Text("Signed in" + (login.account?.let { " as $it" } ?: "") + ". Pick \"Sign-in account\" under Runs on to use it for ${login.name}.", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                else -> {
                    Text("The sign-in did not finish" + (flow.error?.let { ": $it" } ?: "."), color = MaterialTheme.colorScheme.error)
                    Button(onClick = {
                        code = ""
                        viewModel.startLogin(login.engine) { problem -> if (problem != null) error = problem }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

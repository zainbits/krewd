package app.krewd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import app.krewd.Connection
import app.krewd.KrewdViewModel
import app.krewd.R
import app.krewd.UiState
import app.krewd.data.ConfigChoice
import app.krewd.data.SessionInfo

/** "Claude · Sonnet 5.5 · High · Ask first": the chat title's second line. */
fun chatSubtitle(state: UiState, session: SessionInfo): String {
    val engine = state.engine(session.engine)?.name ?: session.engine
    val values = settingsSummary(session.config, { it.current }, session.autoApprove, session.modes.firstOrNull { it.id == session.mode }?.name)
    return (listOf(engine) + values.map { (label, value) -> if (label == "Approvals" && value == "Auto") "Auto-approve" else value }).joinToString(" · ")
}

/**
 * Everything about the open chat, in the quick panel its title opens into ([ChatQuickPanel]): the agent
 * (switching hands over), the agent's own settings (model, effort, approvals), auto-approve, and the chat's
 * actions. Changes apply from the agent's next step. [onClose] folds the panel before an action that leaves it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.ChatSettingsContent(state: UiState, viewModel: KrewdViewModel, session: SessionInfo, onClose: () -> Unit, onSideSession: () -> Unit) {
    val close = onClose
    var switchTo by remember { mutableStateOf<String?>(null) }
    var confirmCompact by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val online = state.connection is Connection.Online
    if (state.engines.size > 1) {
        SheetLabel("Agent")
        CrewPicker(crewMembers(state), session.engine, onPick = { if (it != session.engine) switchTo = it })
        Text(
            "Switching hands the chat over with a summary.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }

    session.parentId?.let { parentId ->
        val parentTitle = state.session(parentId)?.title ?: session.parentTitle ?: "the parent chat"
        SheetLabel(session.sideLabel?.let { "$it of $parentTitle" } ?: "Side session")
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(enabled = online) { viewModel.setReturns(session.id, !session.returns) }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Send replies to $parentTitle", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (session.returns) "On: each reply this chat finishes goes back there (a sub-agent)."
                    else "Off: nothing goes back (a side session).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = session.returns, onCheckedChange = { viewModel.setReturns(session.id, it) }, enabled = online)
        }
    }

    AgentOptions(
        options = session.config,
        selected = { it.current },
        enabled = online,
        onPick = { option, value -> viewModel.setConfig(session.id, option.id, value) },
        autoApprove = session.autoApprove,
        onAutoApprove = { viewModel.setAutoApprove(session.id, it) },
    )
    // Agents that only offer the older ACP modes list.
    if (session.config.none { it.isMode } && session.modes.isNotEmpty()) {
        SheetLabel("Mode")
        ListChoice(session.modes.map { ConfigChoice(it.id, it.name, it.description) }, session.mode, online) { viewModel.setMode(session.id, it) }
    }

    val facts = listOfNotNull(
        session.agent?.let { "Harness: ${it.label}" },
        session.modelUsed?.let { "Last step ran on $it" },
        session.contextPercent?.let { "Context $it% full" },
    )
    if (facts.isNotEmpty()) {
        Text(
            facts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 14.dp),
        )
    }

    FlowRow(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        session.parentId?.let { parentId ->
            if (state.session(parentId) != null) ActionButton(R.drawable.ic_chat_24, "Open parent") { close(); viewModel.openSession(parentId) }
        }
        ActionButton(R.drawable.ic_add_24, "New side session") { onSideSession() }
        ActionButton(R.drawable.ic_compress_24, "Compact") { confirmCompact = true }
        ActionButton(R.drawable.ic_edit_24, "Rename") { renaming = true }
        ActionButton(if (session.pinned) R.drawable.ic_unpin_24 else R.drawable.ic_pin_24, if (session.pinned) "Unpin" else "Pin") { viewModel.setPinned(session.id, !session.pinned) }
        ActionButton(R.drawable.ic_web_24, "Watch the browser") { close(); viewModel.openBrowserAt(session.browserTab?.id) }
        session.routineId?.let { routineId ->
            ActionButton(R.drawable.ic_schedule_24, "Routine") {
                close()
                viewModel.closeSession()
                viewModel.openRoutine(routineId)
            }
        }
        ActionButton(R.drawable.ic_close_24, "Archive") { close(); viewModel.archive(session.id) }
    }

    switchTo?.let { target ->
        val current = state.engine(session.engine)?.name ?: session.engine
        val next = state.engine(target)?.name ?: target
        AlertDialog(
            onDismissRequest = { switchTo = null },
            title = { Text("Switch to $next?") },
            text = {
                Text("$current writes a short handover, then $next continues in this chat. Each agent keeps its own session: switching back resumes it and briefs it on what it missed.")
            },
            confirmButton = { TextButton(onClick = { switchTo = null; viewModel.switchEngine(session.id, target) }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = { switchTo = null }) { Text("Cancel") } },
        )
    }
    if (renaming) RenameDialog(session.title, onDismiss = { renaming = false }) { renaming = false; viewModel.rename(session.id, it) }
    if (confirmCompact) {
        AlertDialog(
            onDismissRequest = { confirmCompact = false },
            title = { Text("Compact the context?") },
            text = {
                Text(
                    (session.contextPercent?.let { "The agent's context is $it% full. " } ?: "") +
                        if (session.compact == "native") "The agent summarises the chat so far with its own /compact and carries on with the summary."
                        else "The agent writes a handover, then a fresh agent session continues from it.\n\nThe chat here stays as it is either way. It runs after the current turn.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmCompact = false; viewModel.compact(session.id) }) { Text("Compact") } },
            dismissButton = { TextButton(onClick = { confirmCompact = false }) { Text("Cancel") } },
        )
    }
}

/** The chat's title, typed by the owner. Once renamed, the agent no longer retitles the chat. */
@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    val title = value.text.trim()
    val ok = title.isNotEmpty() && title.length <= 200
    val save = { if (ok) onSave(title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.copy(text = it.text.replace('\n', ' ')) },
                singleLine = true,
                label = { Text("Title") },
                isError = title.length > 200,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = save, enabled = ok && title != current) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun ActionButton(icon: Int, label: String, danger: Boolean = false, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        colors = if (danger) ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) else ButtonDefaults.filledTonalButtonColors(),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 6.dp))
    }
}

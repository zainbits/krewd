package app.crew.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.crew.Connection
import app.crew.CrewViewModel
import app.crew.R
import app.crew.UiState
import app.crew.data.ConfigChoice
import app.crew.data.SessionInfo
import kotlinx.coroutines.launch

/** "Claude · Sonnet 5.5 · High · Ask first": the chat title's second line. */
fun chatSubtitle(state: UiState, session: SessionInfo): String {
    val engine = state.engine(session.engine)?.name ?: session.engine
    val values = settingsSummary(session.config, { it.current }, session.autoApprove, session.modes.firstOrNull { it.id == session.mode }?.name)
    return (listOf(engine) + values.map { (label, value) -> if (label == "Approvals" && value == "Auto") "Auto-approve" else value }).joinToString(" · ")
}

/**
 * Everything about the open chat in one sheet from its title: the agent (switching hands over), the agent's
 * own settings, auto-approve, and the chat's actions. Changes apply from the agent's next step.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatSettingsSheet(state: UiState, viewModel: CrewViewModel, session: SessionInfo, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }
    var switchTo by remember { mutableStateOf<String?>(null) }
    var confirmCompact by remember { mutableStateOf(false) }
    val online = state.connection is Connection.Online

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp)) {
            Text("Chat settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
            Text(session.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 2.dp))

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
                ActionButton(R.drawable.ic_compress_24, "Compact") { confirmCompact = true }
                ActionButton(R.drawable.ic_pin_24, if (session.pinned) "Unpin" else "Pin") { viewModel.setPinned(session.id, !session.pinned) }
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
        }
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

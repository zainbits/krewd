package app.crew.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.crew.Connection
import app.crew.CrewViewModel
import app.crew.NEW_DRAFT
import app.crew.R
import app.crew.UiState
import app.crew.data.ConfigOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The new task, as a sheet over the Crew tab: what to do, who does it (tap a face), one settings line, and
 * saved prompts. [initialEngine] is the agent whose face was tapped, else the last one used.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NewTaskSheet(state: UiState, viewModel: CrewViewModel, initialEngine: String?, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }
    var engineId by remember { mutableStateOf(initialEngine ?: viewModel.lastEngine()) }
    val engine = state.engines.firstOrNull { it.id == engineId } ?: state.engines.firstOrNull { it.isDefault } ?: state.engines.firstOrNull()
    val options = ConfigOption.ordered(viewModel.engineConfig(engine?.id))
    // Each agent starts with the owner's last picks for it (model, effort, permission mode, auto-approve).
    val saved = remember(engine?.id) { viewModel.engineDefaults(engine?.id) }
    var autoApprove by remember(engine?.id) { mutableStateOf(saved.autoApprove) }
    // Picks that differ from the engine's defaults; only these are sent.
    var picks by remember(engine?.id, options.size) {
        mutableStateOf(
            saved.config.filter { (id, value) ->
                val option = options.firstOrNull { it.id == id }
                option != null && value != option.current && option.choices.any { it.value == value }
            },
        )
    }
    var settingsOpen by remember { mutableStateOf(false) }
    val online = state.connection is Connection.Online
    val text = state.newTaskText
    // The field keeps its own cursor; the text itself lives in the view model (saved prompts set it too).
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
    val query = if (field.selection.collapsed) commandQuery(field.text, field.selection.start) else null
    val matches = remember(engine?.commands, query) { matchCommands(engine?.commands.orEmpty(), query) }
    val files = state.draft(NEW_DRAFT)
    val pickFiles = rememberFilePicker { viewModel.attach(NEW_DRAFT, it) }
    val focus = remember { FocusRequester() }
    val canSend = online && (text.isNotBlank() || files.any { it.uploadId != null }) && files.none { it.uploading }
    val selected: (ConfigOption) -> String? = { picks[it.id] ?: it.current }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(online, engine?.id, options.isEmpty()) {
        // A new engine is probed in the background; ask again shortly so its choices appear.
        if (online && engine != null && options.isEmpty()) repeat(5) {
            delay(4_000)
            viewModel.refreshEngines()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().imePadding()) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp),
        ) {
            when (val c = state.connection) {
                is Connection.Offline -> Text("Can't reach Crew: ${c.reason}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Connection.NotSetUp -> TextButton(onClick = { close(); onOpenSettings() }) { Text("Set up the connection first") }
                else -> {}
            }
            Text("Ask Crew", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
            if (query != null) {
                CommandSuggestions(matches, onPick = {
                    field = applyCommand(field, query, it)
                    viewModel.setNewTaskText(field.text)
                }, Modifier.padding(top = 6.dp))
            }
            TextField(
                value = field,
                onValueChange = {
                    field = it
                    viewModel.setNewTaskText(it.text)
                },
                placeholder = { Text("What should Crew do?", fontSize = 22.sp) },
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium, lineHeight = 30.sp),
                minLines = 3,
                maxLines = 8,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            PendingFiles(files, onRemove = { viewModel.removeAttachment(NEW_DRAFT, it) })
            if (state.engines.isNotEmpty()) {
                SheetLabel("Who")
                CrewPicker(crewMembers(state), engine?.id, onPick = {
                    engineId = it
                    viewModel.setNewTaskEngine(it)
                })
                // Set up in Settings > Agents (its sign-in, or the profile it runs on); shown here only when it stops a task.
                engine?.problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                }
            }
            if (engine != null) {
                SettingsLine(
                    settingsSummary(options, selected, autoApprove),
                    onClick = { settingsOpen = true },
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            SheetLabel("Saved prompts", trailing = if (state.savedPrompts.isNotEmpty()) "Long-press to remove" else null)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.savedPrompts.forEach { prompt ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.combinedClickable(
                            onClick = { viewModel.setNewTaskText(prompt) },
                            onLongClick = {
                                viewModel.forgetPrompt(prompt)
                                viewModel.toast("Removed the saved prompt")
                            },
                        ),
                    ) {
                        Text(
                            prompt,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
                val canSave = text.isNotBlank() && text.trim() !in state.savedPrompts
                Surface(
                    onClick = { viewModel.savePrompt(text) },
                    enabled = canSave,
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        "+ Save this",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (canSave) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = pickFiles, enabled = online, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_attach_24), contentDescription = "Attach files")
                }
                Spacer(Modifier.weight(1f))
                FilledIconButton(
                    onClick = {
                        viewModel.newTask(text.trim(), autoApprove, engine?.id, picks)
                        viewModel.setNewTaskText("")
                        close()
                    },
                    enabled = canSend,
                    shape = RoundedCornerShape(24.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_send_24), contentDescription = "Start task", modifier = Modifier.size(26.dp))
                }
            }
        }
    }

    if (settingsOpen && engine != null) {
        ModalBottomSheet(
            onDismissRequest = { settingsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp)) {
                Text("${engine.name} settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                Text(
                    "For this task. Crew remembers your picks for ${engine.name}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
                if (options.isEmpty()) {
                    Text(
                        "${engine.name} has not listed its settings yet. They appear here once it has started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp, 16.dp),
                    )
                }
                AgentOptions(
                    options = options,
                    selected = selected,
                    enabled = online,
                    onPick = { option, value ->
                        picks = if (value == option.current) picks - option.id else picks + (option.id to value)
                        viewModel.rememberPick(engine.id, option.id, value)
                    },
                    autoApprove = autoApprove,
                    onAutoApprove = {
                        autoApprove = it
                        viewModel.rememberAutoApprove(engine.id, it)
                    },
                )
            }
        }
    }
}

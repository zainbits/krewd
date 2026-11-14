package app.krewd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.krewd.Connection
import app.krewd.KrewdViewModel
import app.krewd.R
import app.krewd.UiState
import app.krewd.constants.ShellDimensions
import app.krewd.data.ConfigOption
import app.krewd.data.Routine
import app.krewd.data.RoutineRun
import app.krewd.data.RoutineTrigger
import app.krewd.data.SchedulePreview
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The Routines tab: every routine with its schedule, next run and last result. */

/** Examples on the empty Routines card: jobs that need a real agent with a browser and a terminal, not a reminder app. */
private val ROUTINE_IDEAS = listOf(
    "every night at 2, pull my repos, run the tests, fix what broke and open a PR for me to review",
    "every morning at 7, check Google Flights for Mumbai to Dubai in December and tell me if a fare drops under ₹18,000",
    "every Monday at 9, read my competitors' pricing and changelog pages and tell me exactly what changed",
    "every weekday at 6 pm, triage new GitHub issues on my repo, reproduce the bugs and draft the fixes",
    "every Sunday at 8, read the week's new arXiv papers on AI agents and write me a five-minute brief",
    "every 6 hours, watch the Pixel 10 Pro on Amazon and Flipkart and tell me when it's in stock under ₹70,000",
)
@Composable
fun RoutinesScreen(state: UiState, viewModel: KrewdViewModel, padding: PaddingValues) {
    var creating by remember { mutableStateOf(false) }
    val online = state.connection is Connection.Online
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "new") {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Routines", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Instructions Krewd runs on a schedule. Every run goes into the routine's own chat, so you can reply, review and compact it there.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { creating = true }, enabled = online) {
                        Icon(painterResource(R.drawable.ic_add_24), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New routine")
                    }
                }
                val idea = remember { ROUTINE_IDEAS.random() }
                Text(
                    "Or ask Krewd in a task, e.g. “$idea”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.routines.isEmpty() && online) {
            item(key = "empty") {
                Text(
                    "No routines yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
        items(state.routines, key = { it.id }) { routine ->
            RoutineCard(
                routine = routine,
                chatStatus = state.sessions.firstOrNull { it.id == routine.sessionId }?.status ?: routine.sessionStatus,
                engineName = state.engine(routine.engine)?.name ?: routine.engine,
                onClick = { viewModel.openRoutine(routine.id) },
                onToggle = { viewModel.setRoutineEnabled(routine.id, it) },
            )
        }
    }
    if (creating) {
        RoutineEditor(state, viewModel, existing = null, onDismiss = { creating = false }, onSaved = {
            creating = false
            viewModel.openRoutine(it.id)
        })
    }
}

@Composable
private fun RoutineCard(routine: Routine, chatStatus: String?, engineName: String, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(ShellDimensions.CARD_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    painterResource(R.drawable.ic_schedule_24),
                    contentDescription = null,
                    tint = if (routine.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
                Text(routine.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Switch(checked = routine.enabled, onCheckedChange = onToggle)
            }
            Text(
                routine.scheduleText + if (routine.enabled) " · next ${whenText(routine.nextRunAt)} (${untilText(routine.nextRunAt)})" else " · paused",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val last = routine.lastRun
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (chatStatus == "running" || chatStatus == "waiting") StatusChip(chatStatus)
                else if (last != null) RunChip(last.status)
                Text(
                    if (last == null) "Never ran · $engineName" else "Last ${shortAge(last.createdAt)} ago · v${routine.version} · $engineName",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            last?.summary?.let { summary ->
                Text(
                    plainLine(summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One routine's page: schedule, instructions and every run (tap a run to see it in the chat). */
@Composable
fun RoutineScreen(state: UiState, viewModel: KrewdViewModel) {
    val routine = state.openRoutine
    var editing by remember { mutableStateOf(false) }
    var editingNotes by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val bars = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues()
    val bottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (routine != null) {
            val online = state.connection is Connection.Online
            val chat = state.sessions.firstOrNull { it.id == routine.sessionId }
            val busy = chat?.isBusy == true || routine.lastRun?.active == true
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ShellDimensions.CONTENT_HORIZONTAL_PADDING,
                    end = ShellDimensions.CONTENT_HORIZONTAL_PADDING,
                    top = bars.calculateTopPadding() + ShellDimensions.TITLE_PILL_TOP_GAP +
                        ShellDimensions.TITLE_PILL_HEIGHT + ShellDimensions.CONTENT_CLEARANCE,
                    bottom = bottom + ShellDimensions.CONTENT_CLEARANCE,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "schedule") {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(if (routine.enabled) "On" else "Paused", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    routine.timezone + " · " + if (routine.enabled) "next ${whenText(routine.nextRunAt)} (${untilText(routine.nextRunAt)})" else "no scheduled runs until you turn it on",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = routine.enabled, onCheckedChange = { viewModel.setRoutineEnabled(routine.id, it) }, enabled = online)
                        }
                        Text("Triggers", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                        routine.cronTriggers.forEach { TriggerLine(it, routine.enabled) }
                        routine.followUps.forEach { follow ->
                            FollowUpLine(follow, onCancel = { viewModel.cancelFollowUp(routine.id, follow.id) }, enabled = online)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            var runMenu by remember { mutableStateOf(false) }
                            Box {
                                Button(
                                    onClick = { if (routine.cronTriggers.size > 1) runMenu = true else viewModel.runRoutine(routine.id) },
                                    enabled = online && !busy,
                                ) {
                                    Icon(painterResource(R.drawable.ic_play_24), contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (busy) "Running" else "Run now")
                                }
                                DropdownMenu(expanded = runMenu, onDismissRequest = { runMenu = false }) {
                                    routine.cronTriggers.forEach { t ->
                                        DropdownMenuItem(text = { Text("Run as ${t.name}") }, onClick = {
                                            runMenu = false
                                            viewModel.runRoutine(routine.id, t.name)
                                        })
                                    }
                                }
                            }
                            OutlinedButton(onClick = { viewModel.openRoutineChat(routine) }, enabled = routine.sessionId != null) {
                                Icon(painterResource(R.drawable.ic_chat_24), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open chat")
                            }
                        }
                        Text(
                            listOfNotNull(
                                "Agent: ${state.engine(routine.engine)?.name ?: routine.engine}",
                                if (routine.autoApprove) "auto-approve on" else "you OK agent prompts",
                                if (routine.createdBy == "agent") "made by Krewd" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item(key = "instructions") {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Instructions · v${routine.version}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { editing = true }, enabled = online) {
                                Icon(painterResource(R.drawable.ic_edit_24), contentDescription = "Edit routine")
                            }
                        }
                        SelectionContainer { Text(routine.instructions, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                item(key = "notes") {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Notes · what Krewd learned", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { editingNotes = true }, enabled = online) {
                                Icon(painterResource(R.drawable.ic_edit_24), contentDescription = "Edit notes")
                            }
                        }
                        if (routine.notes.isBlank()) {
                            Text(
                                "Nothing yet. Each run reads these, and Krewd adds what it learns (links, where buttons are, exact names). You can edit them too.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            SelectionContainer { Text(routine.notes, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
                item(key = "runs-title") {
                    Text(
                        "Runs",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                    )
                }
                val runs = state.routineDetail?.takeIf { it.id == routine.id }?.runs
                if (runs == null) {
                    item(key = "runs-loading") {
                        Text("Loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                    }
                } else if (runs.isEmpty()) {
                    item(key = "runs-empty") {
                        Text("No runs yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                    }
                } else {
                    items(runs, key = { it.id }) { run ->
                        RunRow(run, onClick = { viewModel.openRoutineChat(routine, run) })
                    }
                }
            }
        }
        RoutineTopBar(
            title = routine?.title ?: "Routine",
            enabled = routine?.enabled,
            onBack = viewModel::closeRoutine,
            onEdit = { editing = true },
            onToggle = { routine?.let { viewModel.setRoutineEnabled(it.id, !it.enabled) } },
            onDelete = { confirmDelete = true },
        )
    }
    if (editing && routine != null) {
        RoutineEditor(state, viewModel, existing = routine, onDismiss = { editing = false }, onSaved = { editing = false })
    }
    if (editingNotes && routine != null) {
        NotesEditor(routine.notes, onDismiss = { editingNotes = false }, onSave = {
            editingNotes = false
            viewModel.saveRoutineNotes(routine.id, it)
        })
    }
    if (confirmDelete && routine != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this routine?") },
            text = { Text("“${routine.title}” and its run history are deleted. Its chat stays as an ordinary task.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteRoutine(routine.id)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RoutineTopBar(
    title: String,
    enabled: Boolean?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = ShellDimensions.TITLE_PILL_START_MARGIN)
            .padding(top = ShellDimensions.TITLE_PILL_TOP_GAP),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FloatingPill {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_chevron_left_24), contentDescription = "Back") }
        }
        FloatingPill(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (enabled != null) Box {
            FloatingPill {
                IconButton(onClick = { menuOpen = true }) { Icon(painterResource(R.drawable.ic_more_24), contentDescription = "Routine options") }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                DropdownMenuItem(text = { Text(if (enabled) "Pause" else "Resume") }, onClick = { menuOpen = false; onToggle() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Delete routine", color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

@Composable
private fun RunRow(run: RoutineRun, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = run.seq != null, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(whenText(run.scheduledFor ?: run.createdAt), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(
                    listOfNotNull(
                        if (run.trigger == "manual") "Run now" else if (run.attempt > 1) "Retry" else "Scheduled",
                        run.triggers.filter { it != "main" }.takeIf { it.isNotEmpty() }?.joinToString(", "),
                        if (run.maxAttempts > 1) "${run.attempt}/${run.maxAttempts}" else null,
                        "v${run.version}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RunChip(run.status)
            }
            run.summary?.let {
                Text(plainLine(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun RunChip(status: String) {
    val (label, color) = when (status) {
        "done" -> "Done" to MaterialTheme.colorScheme.primary
        "running" -> "Running" to MaterialTheme.colorScheme.primary
        "queued" -> "Queued" to MaterialTheme.colorScheme.tertiary
        "error" -> "Error" to MaterialTheme.colorScheme.error
        "cancelled" -> "Stopped" to MaterialTheme.colorScheme.outline
        "skipped" -> "Skipped" to MaterialTheme.colorScheme.outline
        else -> status to MaterialTheme.colorScheme.outline
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun TriggerLine(t: RoutineTrigger, routineOn: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            painterResource(R.drawable.ic_schedule_24),
            contentDescription = null,
            tint = if (t.enabled && routineOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                (if (t.name == "main") "" else "${t.name} · ") + (t.scheduleText ?: t.schedule ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                listOfNotNull(
                    t.schedule,
                    if (t.maxAttempts > 1) "up to ${t.maxAttempts} tries" else "1 try",
                    if (!t.enabled) "off" else if (routineOn) "next ${whenText(t.nextRunAt)}" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A retry a run asked for ("try again at 19:05"), with Cancel. */
@Composable
private fun FollowUpLine(f: RoutineTrigger, onCancel: () -> Unit, enabled: Boolean) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Retry of ${f.parent ?: f.name} at ${whenText(f.nextRunAt)} · attempt ${f.attempt ?: 2} of ${f.maxAttempts}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                f.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer) }
            }
            IconButton(onClick = onCancel, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Cancel retry", tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun NotesEditor(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Routine notes") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(8000) }, minLines = 6, maxLines = 14,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                placeholder = { Text("Links, where things are, exact names: anything each run should know.") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A trigger being edited: [key] keeps rows apart while names change. */
private data class TriggerDraft(val key: Long, val name: String, val schedule: String, val attempts: Int, val enabled: Boolean)

private var nextDraftKey = 1L

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggerDraftCard(
    draft: TriggerDraft,
    timezone: String?,
    viewModel: KrewdViewModel,
    canRemove: Boolean,
    duplicate: Boolean,
    onChange: (TriggerDraft) -> Unit,
    onRemove: () -> Unit,
    onValidity: (Boolean) -> Unit,
) {
    var preview by remember { mutableStateOf<SchedulePreview?>(null) }
    var presets by remember { mutableStateOf(false) }
    LaunchedEffect(draft.schedule) {
        delay(350)
        preview = withContext(Dispatchers.IO) { runCatching { viewModel.previewSchedule(draft.schedule.trim(), timezone) }.getOrNull() }
        onValidity(preview?.error != null)
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.name, onValueChange = { onChange(draft.copy(name = it.take(40))) },
                    label = { Text("Name") }, singleLine = true, isError = duplicate || draft.name.isBlank(),
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                    supportingText = if (duplicate) ({ Text("Two triggers have this name") }) else null,
                )
                Switch(checked = draft.enabled, onCheckedChange = { onChange(draft.copy(enabled = it)) })
                if (canRemove) IconButton(onClick = onRemove) { Icon(painterResource(R.drawable.ic_delete_24), contentDescription = "Remove trigger") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft.schedule, onValueChange = { onChange(draft.copy(schedule = it)) },
                    label = { Text("minute hour day month weekday") },
                    singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    isError = preview?.error != null,
                    supportingText = {
                        val p = preview
                        when {
                            p == null -> Text("Checking…")
                            p.error != null -> Text(p.error, color = MaterialTheme.colorScheme.error)
                            else -> Text("${p.text}. Next: " + p.nextRuns.take(2).joinToString(" · ") { whenText(it) })
                        }
                    },
                )
                Box {
                    TextButton(onClick = { presets = true }) { Text("Presets") }
                    DropdownMenu(expanded = presets, onDismissRequest = { presets = false }) {
                        PRESETS.forEach { (label, cron) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { presets = false; onChange(draft.copy(schedule = cron)) })
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tries per firing", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(draft.copy(attempts = (draft.attempts - 1).coerceAtLeast(1))) }, enabled = draft.attempts > 1) { Text("−") }
                Text("${draft.attempts}", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onChange(draft.copy(attempts = (draft.attempts + 1).coerceAtMost(10))) }, enabled = draft.attempts < 10) { Text("+") }
            }
        }
    }
}

private val PRESETS = listOf(
    "Weekdays 19:00" to "0 19 * * 1-5",
    "Every day 09:00" to "0 9 * * *",
    "Fridays 18:00" to "0 18 * * 5",
    "Month's last day 19:00" to "0 19 L * *",
    "Month's last weekday 18:00" to "0 18 LW * *",
    "Every hour" to "0 * * * *",
)

/** Create or edit a routine, full screen. The schedule is checked on the server as you type. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineEditor(state: UiState, viewModel: KrewdViewModel, existing: Routine?, onDismiss: () -> Unit, onSaved: (Routine) -> Unit) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    val drafts = remember {
        mutableStateListOf<TriggerDraft>().apply {
            val start = existing?.cronTriggers.orEmpty()
            if (start.isEmpty()) add(TriggerDraft(nextDraftKey++, "main", "0 19 * * 1-5", 1, true))
            else start.forEach { add(TriggerDraft(nextDraftKey++, it.name, it.schedule ?: "", it.maxAttempts, it.enabled)) }
        }
    }
    val badSchedules = remember { mutableStateMapOf<Long, Boolean>() }
    var instructions by remember { mutableStateOf(existing?.instructions ?: "") }
    var engineId by remember { mutableStateOf(existing?.engine ?: state.engines.firstOrNull { it.isDefault }?.id) }
    var autoApprove by remember { mutableStateOf(existing?.autoApprove ?: false) }
    // The agent's own settings (model, effort, permission mode...): the routine's picks for its agent, else the
    // last picks for that agent from the new-task card. A different agent starts from its own.
    val options = ConfigOption.ordered(viewModel.engineConfig(engineId))
    var picks by remember(engineId, options.size) {
        mutableStateOf(
            (if (existing != null && engineId == existing.engine) existing.config else viewModel.engineDefaults(engineId).config)
                .filter { (id, value) -> options.any { o -> o.id == id && o.choices.any { it.value == value } } },
        )
    }
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var engineMenu by remember { mutableStateOf(false) }

    val names = drafts.map { it.name.trim().lowercase() }
    val valid = title.isNotBlank() && instructions.isNotBlank() && drafts.isNotEmpty() &&
        drafts.all { it.schedule.isNotBlank() && it.name.isNotBlank() && badSchedules[it.key] != true } &&
        names.toSet().size == names.size

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Cancel") }
                    Text(if (existing == null) "New routine" else "Edit routine", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Button(
                        enabled = valid && !saving,
                        onClick = {
                            saving = true
                            val fields = JSONObject()
                                .put("title", title.trim())
                                .put("triggers", JSONArray(drafts.map {
                                    JSONObject().put("name", it.name.trim().lowercase()).put("schedule", it.schedule.trim())
                                        .put("max_attempts", it.attempts).put("enabled", it.enabled)
                                }))
                                .put("instructions", instructions.trim())
                                .put("auto_approve", autoApprove)
                            engineId?.let { fields.put("engine", it) }
                            fields.put("config", JSONObject(picks))
                            if (existing != null && note.isNotBlank()) fields.put("note", note.trim())
                            // A refused save shows its reason as a message, and the owner can try again.
                            viewModel.saveRoutine(existing?.id, fields, failed = { saving = false }) { saving = false; onSaved(it) }
                        },
                    ) { Text("Save") }
                }
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, label = { Text("Title") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                )
                Text("Triggers: when it runs", style = MaterialTheme.typography.labelLarge)
                drafts.forEachIndexed { index, draft ->
                    TriggerDraftCard(
                        draft = draft,
                        timezone = existing?.timezone,
                        viewModel = viewModel,
                        canRemove = drafts.size > 1,
                        duplicate = names.count { it == draft.name.trim().lowercase() } > 1,
                        onChange = { drafts[index] = it },
                        onRemove = { badSchedules.remove(draft.key); drafts.removeAt(index) },
                        onValidity = { bad -> badSchedules[draft.key] = bad },
                    )
                }
                if (drafts.size < 10) {
                    TextButton(onClick = {
                        val name = if (drafts.size == 1 && drafts[0].name == "main") "second" else "trigger ${drafts.size + 1}"
                        drafts.add(TriggerDraft(nextDraftKey++, name, "0 9 * * 1-5", 1, true))
                    }) {
                        Icon(painterResource(R.drawable.ic_add_24), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add trigger")
                    }
                }
                Text(
                    "Several triggers make one routine with one chat (a morning scan and an evening report). Each run is told which trigger fired, today's date and the month's last day; write a part per trigger in the instructions. More than 1 try lets a run ask Krewd to retry later (\u201cthe build is still running\u201d), never more than that.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = instructions, onValueChange = { instructions = it }, label = { Text("Instructions") },
                    minLines = 6, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("What should Krewd do on each run? Say what needs your review before it submits anything.") },
                )
                if (existing != null) {
                    OutlinedTextField(
                        value = note, onValueChange = { note = it }, label = { Text("What changed (optional, kept with the version)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Agent", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { engineMenu = true }) { Text(state.engine(engineId)?.name ?: engineId ?: "Default") }
                        DropdownMenu(expanded = engineMenu, onDismissRequest = { engineMenu = false }) {
                            state.engines.forEach { engine ->
                                DropdownMenuItem(text = { Text(engine.name) }, onClick = { engineId = engine.id; engineMenu = false })
                            }
                        }
                    }
                }
                if (options.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { option ->
                            ConfigChip(
                                option = option,
                                selected = picks[option.id] ?: option.current,
                                enabled = true,
                                onPick = { value -> picks = picks + (option.id to value) },
                            )
                        }
                    }
                }
                if (existing != null && engineId != existing.engine) {
                    Text(
                        "The routine's chat moves to this agent: the current agent writes a handover first, and switching back later resumes its own session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-approve", style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (autoApprove) "Agent prompts are answered for you during runs" else "You OK each agent prompt (runs wait for you)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoApprove, onCheckedChange = { autoApprove = it })
                }
                Spacer(Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

/** "Fri 13 Nov, 19:00" in the phone's timezone. */
fun whenText(iso: String?): String = iso?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(DAY_TIME) }.getOrNull()
} ?: "-"

/** "in 40 min", "in 5 h", "in 3 d". */
fun untilText(iso: String?): String {
    val at = iso?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return "-"
    val minutes = Duration.between(Instant.now(), at).toMinutes()
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "in $minutes min"
        minutes < 48 * 60 -> "in ${minutes / 60} h"
        else -> "in ${minutes / (24 * 60)} d"
    }
}

/** The first lines of an agent reply without Markdown marks, for previews. */
fun plainLine(text: String): String =
    text.lines().map { it.trim().replace(Regex("^#+\\s*|\\*\\*|__|`"), "") }.filter { it.isNotEmpty() }.joinToString(" ")

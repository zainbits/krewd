package app.crew.ui

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
import app.crew.Connection
import app.crew.CrewViewModel
import app.crew.R
import app.crew.UiState
import app.crew.constants.ShellDimensions
import app.crew.data.Routine
import app.crew.data.RoutineRun
import app.crew.data.SchedulePreview
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The Routines tab: every routine with its schedule, next run and last result. */
@Composable
fun RoutinesScreen(state: UiState, viewModel: CrewViewModel, padding: PaddingValues) {
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
                            "Instructions Crew runs on a schedule. Every run goes into the routine's own chat, so you can reply, review and compact it there.",
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
                Text(
                    "Or ask Crew in a task, e.g. “check me out on Zoho every weekday at 7 pm”.",
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
fun RoutineScreen(state: UiState, viewModel: CrewViewModel) {
    val routine = state.openRoutine
    var editing by remember { mutableStateOf(false) }
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
                                Text(routine.scheduleText, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${routine.schedule} · ${routine.timezone}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = routine.enabled, onCheckedChange = { viewModel.setRoutineEnabled(routine.id, it) }, enabled = online)
                        }
                        Text(
                            if (!routine.enabled) "Paused: no scheduled runs until you turn it on."
                            else "Next: " + routine.nextRuns.take(3).joinToString(" · ") { whenText(it) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.runRoutine(routine.id) }, enabled = online && !busy) {
                                Icon(painterResource(R.drawable.ic_play_24), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (busy) "Running" else "Run now")
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
                                if (routine.createdBy == "agent") "made by Crew" else null,
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
                    (if (run.trigger == "schedule") "Scheduled" else "Run now") + " · v${run.version}",
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

private val PRESETS = listOf(
    "Weekdays 19:00" to "0 19 * * 1-5",
    "Every day 09:00" to "0 9 * * *",
    "Fridays 18:00" to "0 18 * * 5",
    "Month's last day 19:00" to "0 19 L * *",
    "Every hour" to "0 * * * *",
)

/** Create or edit a routine, full screen. The schedule is checked on the server as you type. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineEditor(state: UiState, viewModel: CrewViewModel, existing: Routine?, onDismiss: () -> Unit, onSaved: (Routine) -> Unit) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var schedule by remember { mutableStateOf(existing?.schedule ?: "0 19 * * 1-5") }
    var instructions by remember { mutableStateOf(existing?.instructions ?: "") }
    var engineId by remember { mutableStateOf(existing?.engine ?: state.engines.firstOrNull { it.isDefault }?.id) }
    var autoApprove by remember { mutableStateOf(existing?.autoApprove ?: false) }
    var note by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<SchedulePreview?>(null) }
    var saving by remember { mutableStateOf(false) }
    var engineMenu by remember { mutableStateOf(false) }

    LaunchedEffect(schedule) {
        delay(350)
        preview = withContext(Dispatchers.IO) { runCatching { viewModel.previewSchedule(schedule.trim(), existing?.timezone) }.getOrNull() }
    }
    val valid = title.isNotBlank() && instructions.isNotBlank() && preview?.error == null && schedule.isNotBlank()

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
                                .put("schedule", schedule.trim())
                                .put("instructions", instructions.trim())
                                .put("auto_approve", autoApprove)
                            engineId?.let { fields.put("engine", it) }
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
                Text("When", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.forEach { (label, cron) ->
                        FilterChip(selected = schedule.trim() == cron, onClick = { schedule = cron }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    value = schedule, onValueChange = { schedule = it },
                    label = { Text("Schedule (minute hour day month weekday)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    isError = preview?.error != null,
                    supportingText = {
                        val p = preview
                        when {
                            p == null -> Text("Checking…")
                            p.error != null -> Text(p.error, color = MaterialTheme.colorScheme.error)
                            else -> Text("${p.text}. Next: " + p.nextRuns.take(3).joinToString(" · ") { whenText(it) })
                        }
                    },
                )
                Text(
                    "Keep calendar rules in the instructions (“on Fridays fill the week”, “if the month ends mid-week, fill up to the last day”) and run it daily or on weekdays. Each run is told today's date, weekday and the month's last day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = instructions, onValueChange = { instructions = it }, label = { Text("Instructions") },
                    minLines = 6, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("What should Crew do on each run? Say what needs your review before it submits anything.") },
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
                if (existing != null && engineId != existing.engine) {
                    Text(
                        "A chat belongs to one agent: the next run starts a new chat; the current one stays as a task.",
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

package app.krewd.ui

import io.github.zainbits.cohesion.rememberGlassBackdrop
import io.github.zainbits.cohesion.glassSource
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
fun RoutinesScreen(state: UiState, viewModel: KrewdViewModel, padding: PaddingValues, listState: androidx.compose.foundation.lazy.LazyListState) {
    var creating by remember { mutableStateOf(false) }
    val online = state.connection is Connection.Online
    LazyColumn(
        state = listState,
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
                routine.scheduleText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                nextLine(routine),
                style = MaterialTheme.typography.labelMedium,
                color = if (routine.skipping && routine.enabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
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

/** "Next Tue 24 Nov, 09:55 (in 23 h) · skipping 2", or "Paused". */
private fun nextLine(routine: Routine): String {
    if (!routine.enabled) return "Paused"
    val skipped = routine.cronTriggers.sumOf { it.skipRuns }
    val next = routine.nextLiveAt?.let { "Next ${whenText(it)} (${untilText(it)})" } ?: "No coming runs"
    return next + if (skipped > 0) " · skipping $skipped" else ""
}

/** Which part of a routine an editor changes: everything (a new routine) or one section of its page. */
private enum class EditPart { ALL, SCHEDULE, INSTRUCTIONS, AGENT }

/** How many runs a routine's page lists before "Show all". */
private const val RUNS_SHOWN = 5

/**
 * One routine's page, as an overview: status, schedule with skips and what comes next, then instructions and
 * notes folded to a few lines (tap to read them whole), then the latest runs. Each section edits on its own.
 */
@Composable
fun RoutineScreen(state: UiState, viewModel: KrewdViewModel, listState: androidx.compose.foundation.lazy.LazyListState) {
    val routine = state.openRoutine
    var editing by remember { mutableStateOf<EditPart?>(null) }
    var editingNotes by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var skipping by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<String?>(null) }
    var allRuns by remember(routine?.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember(routine?.id) { mutableStateOf(false) }
    val bars = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues()
    val bottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
    // The page scrolls under its glass top bar.
    val glassOn = LocalKrewdGlass.current.on
    val backdrop = rememberGlassBackdrop()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (routine != null) {
            val online = state.connection is Connection.Online
            val chat = state.sessions.firstOrNull { it.id == routine.sessionId }
            val busy = chat?.isBusy == true || routine.lastRun?.active == true
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (glassOn) Modifier.glassSource(backdrop).background(MaterialTheme.colorScheme.surface) else Modifier),
                contentPadding = PaddingValues(
                    start = ShellDimensions.CONTENT_HORIZONTAL_PADDING,
                    end = ShellDimensions.CONTENT_HORIZONTAL_PADDING,
                    top = bars.calculateTopPadding() + ShellDimensions.TITLE_PILL_TOP_GAP +
                        ShellDimensions.TITLE_PILL_HEIGHT + ShellDimensions.CONTENT_CLEARANCE,
                    bottom = bottom + ShellDimensions.CONTENT_CLEARANCE,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "status") {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(if (routine.enabled) "On" else "Paused", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (routine.enabled) nextLine(routine) else "No scheduled runs until you turn it on",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (routine.skipping && routine.enabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = routine.enabled, onCheckedChange = { viewModel.setRoutineEnabled(routine.id, it) }, enabled = online)
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
                                KrewdMenu(expanded = runMenu, onDismiss = { runMenu = false }, backdrop = if (glassOn) backdrop else null, width = 220.dp) {
                                    KrewdMenuHeader("Run now as")
                                    routine.cronTriggers.forEach { t ->
                                        KrewdMenuItem(t.name, t.scheduleText) {
                                            runMenu = false
                                            viewModel.runRoutine(routine.id, t.name)
                                        }
                                    }
                                }
                            }
                            OutlinedButton(onClick = { viewModel.openRoutineChat(routine) }, enabled = routine.sessionId != null) {
                                Icon(painterResource(R.drawable.ic_chat_24), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Latest chat")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                listOfNotNull(
                                    "Agent: ${state.engine(routine.engine)?.name ?: routine.engine}",
                                    if (routine.autoApprove) "auto-approve on" else "you OK agent prompts",
                                    if (routine.createdBy == "agent") "made by Krewd" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { editing = EditPart.AGENT }, enabled = online) {
                                Icon(painterResource(R.drawable.ic_edit_24), contentDescription = "Change agent", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                item(key = "schedule") {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Schedule · ${routine.timezone}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = { skipping = true }, enabled = online && routine.enabled) { Text("Skip…") }
                            IconButton(onClick = { editing = EditPart.SCHEDULE }, enabled = online) {
                                Icon(painterResource(R.drawable.ic_edit_24), contentDescription = "Edit schedule")
                            }
                        }
                        routine.cronTriggers.forEach { t ->
                            TriggerLine(t, routine.enabled, onUndoSkip = { viewModel.skipRoutineRuns(routine.id, t.name, 0) }, online = online)
                        }
                        routine.followUps.forEach { follow ->
                            FollowUpLine(follow, onCancel = { viewModel.cancelFollowUp(routine.id, follow.id) }, enabled = online)
                        }
                        if (routine.enabled && routine.upcoming.isNotEmpty()) {
                            Text("Coming up", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                            val several = routine.cronTriggers.size > 1
                            // Every skipped firing, then enough real ones to show what runs next.
                            val shown = routine.upcoming.filter { it.skipped } + routine.upcoming.filterNot { it.skipped }.take(3)
                            shown.sortedBy { it.at }.forEach { u -> UpcomingLine(u, several) }
                        }
                    }
                }
                item(key = "instructions") {
                    FoldedTextCard(
                        title = "Instructions · v${routine.version}",
                        text = routine.instructions,
                        empty = "No instructions.",
                        onOpen = { reading = "instructions" },
                        onEdit = { editing = EditPart.INSTRUCTIONS },
                        editEnabled = online,
                    )
                }
                item(key = "notes") {
                    FoldedTextCard(
                        title = "Notes · what Krewd learned",
                        text = routine.notes,
                        empty = "Nothing yet. Each run reads these, and Krewd adds what it learns (links, where buttons are, exact names). You can edit them too.",
                        onOpen = { reading = "notes" },
                        onEdit = { editingNotes = true },
                        editEnabled = online,
                    )
                }
                item(key = "runs-title") {
                    Column(Modifier.padding(start = 8.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Runs", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Each run has its own chat. The next run deletes it, unless it failed or waits for you: those stay until you delete them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    items(if (allRuns) runs else runs.take(RUNS_SHOWN), key = { it.id }) { run ->
                        RunRow(run, onClick = { viewModel.openRoutineChat(routine, run) })
                    }
                    if (runs.size > RUNS_SHOWN) {
                        item(key = "runs-more") {
                            TextButton(onClick = { allRuns = !allRuns }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (allRuns) "Show fewer" else "Show all ${runs.size} runs")
                            }
                        }
                    }
                }
            }
        }
        CompositionLocalProvider(LocalKrewdBackdrop provides if (glassOn && routine != null) backdrop else null) {
            RoutineTopBar(
                title = routine?.title ?: "Routine",
                showMenu = routine != null,
                onBack = viewModel::closeRoutine,
                onMenu = { menuOpen = !menuOpen },
            )
        }
        if (menuOpen && routine != null) {
            BackHandler { menuOpen = false }
            Box(
                Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    menuOpen = false
                },
            )
            RoutineMenuCard(
                backdrop = if (glassOn) backdrop else null,
                enabled = routine.enabled,
                onRename = { menuOpen = false; renaming = true },
                onSkip = { menuOpen = false; skipping = true },
                onToggle = { menuOpen = false; viewModel.setRoutineEnabled(routine.id, !routine.enabled) },
                onDelete = { menuOpen = false; confirmDelete = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = bars.calculateTopPadding() + ShellDimensions.TITLE_PILL_TOP_GAP + ShellDimensions.TITLE_PILL_HEIGHT + 8.dp,
                        end = ShellDimensions.TITLE_PILL_START_MARGIN,
                    ),
            )
        }
    }
    if (routine != null) {
        editing?.let { part ->
            RoutineEditor(state, viewModel, existing = routine, part = part, onDismiss = { editing = null }, onSaved = { editing = null })
        }
        reading?.let { which ->
            val isNotes = which == "notes"
            TextReader(
                title = if (isNotes) "Notes" else "Instructions · v${routine.version}",
                text = if (isNotes) routine.notes else routine.instructions,
                onDismiss = { reading = null },
                onEdit = {
                    reading = null
                    if (isNotes) editingNotes = true else editing = EditPart.INSTRUCTIONS
                },
            )
        }
        if (skipping) {
            SkipSheet(routine, backdrop = if (glassOn) backdrop else null, onDismiss = { skipping = false }, onSkip = { trigger, runs, today ->
                viewModel.skipRoutineRuns(routine.id, trigger, runs, today)
            })
        }
        if (renaming) {
            RenameDialog(routine.title, onDismiss = { renaming = false }, onSave = { title ->
                renaming = false
                viewModel.saveRoutine(routine.id, JSONObject().put("title", title))
            })
        }
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
            text = { Text("“${routine.title}” and its run history are deleted. Its run chats stay as ordinary tasks.") },
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

/** Long text folded to a few lines; tap it to read it whole, the pencil to edit. */
@Composable
private fun FoldedTextCard(title: String, text: String, empty: String, onOpen: () -> Unit, onEdit: () -> Unit, editEnabled: Boolean) {
    val lines = remember(text) { text.lines().size }
    val long = lines > FOLDED_LINES || text.length > 360
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onEdit, enabled = editEnabled) {
                Icon(painterResource(R.drawable.ic_edit_24), contentDescription = "Edit")
            }
        }
        if (text.isBlank()) {
            Text(empty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(Modifier.fillMaxWidth().clickable(enabled = long, onClick = onOpen), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = FOLDED_LINES, overflow = TextOverflow.Ellipsis)
                if (long) {
                    Text(
                        "Read all · $lines lines",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private const val FOLDED_LINES = 5

/** A routine's instructions or notes, whole, on their own screen. */
@Composable
private fun TextReader(title: String, text: String, onDismiss: () -> Unit, onEdit: () -> Unit) {
    FullScreenPage(
        onDismiss = onDismiss,
        bar = {
            PageTopBar(
                icon = R.drawable.ic_chevron_left_24, iconLabel = "Back", onIcon = onDismiss, title = title,
                action = "Edit", actionIcon = R.drawable.ic_edit_24, onAction = onEdit,
            )
        },
    ) { contentPadding ->
        SelectionContainer(Modifier.fillMaxSize()) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 20.dp),
            )
        }
    }
}

/**
 * A full-screen page over the app (a routine's editor, its instructions or notes): the content scrolls under a
 * floating liquid [bar], like the routine page and the chat. [content] gets the padding that clears the bar at
 * the top and the gap at the bottom; the keyboard and the navigation bar are kept out already.
 */
@Composable
private fun FullScreenPage(
    onDismiss: () -> Unit,
    bar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val glassOn = LocalKrewdGlass.current.on
        val backdrop = rememberGlassBackdrop()
        val top = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues().calculateTopPadding()
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (glassOn) Modifier.glassSource(backdrop).background(MaterialTheme.colorScheme.surface) else Modifier)
                    .windowInsetsPadding(
                        WindowInsets.ime.union(WindowInsets.navigationBars).union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ),
            ) {
                content(
                    PaddingValues(
                        top = top + ShellDimensions.TITLE_PILL_TOP_GAP + ShellDimensions.TITLE_PILL_HEIGHT + ShellDimensions.CONTENT_CLEARANCE,
                        bottom = ShellDimensions.CONTENT_CLEARANCE,
                    ),
                )
            }
            CompositionLocalProvider(LocalKrewdBackdrop provides if (glassOn) backdrop else null) { bar() }
        }
    }
}

/**
 * A page's top bar: a round button ([icon]: close or back), the title in a pill and an optional [action] pill
 * (Save, Edit), all one liquid glass bar like [RoutineTopBar]'s. A disabled action fades its text.
 */
@Composable
private fun PageTopBar(
    @androidx.annotation.DrawableRes icon: Int,
    iconLabel: String,
    onIcon: () -> Unit,
    title: String,
    action: String? = null,
    @androidx.annotation.DrawableRes actionIcon: Int? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
) {
    LiquidRow(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = ShellDimensions.TITLE_PILL_START_MARGIN)
            .padding(top = ShellDimensions.TITLE_PILL_TOP_GAP),
    ) {
        KrewdIconPill(onClick = onIcon, icon = icon, contentDescription = iconLabel)
        KrewdPill(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            )
        }
        if (action != null) {
            KrewdPill(onClick = onAction, enabled = actionEnabled, contentAlignment = Alignment.Center) {
                val ink = LocalContentColor.current.let { if (actionEnabled) it else it.copy(alpha = 0.38f) }
                Row(
                    Modifier.padding(start = if (actionIcon != null) 14.dp else 20.dp, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (actionIcon != null) {
                        Icon(painterResource(actionIcon), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(action, style = MaterialTheme.typography.titleSmall, color = ink, maxLines = 1)
                }
            }
        }
    }
}

/** Skip coming runs: of every trigger or one, the rest of today or the next few firings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkipSheet(
    routine: Routine,
    backdrop: io.github.zainbits.cohesion.GlassBackdrop?,
    onDismiss: () -> Unit,
    onSkip: (trigger: String?, runs: Int?, today: Boolean) -> Unit,
) {
    val triggers = routine.cronTriggers.filter { it.enabled }
    var trigger by remember { mutableStateOf<String?>(null) }
    val chosen = triggers.filter { trigger == null || it.name == trigger }
    val current = chosen.sumOf { it.skipRuns }
    val sheet = rememberGlassSheetState()
    val pick: (String?, Int?, Boolean) -> Unit = { t, n, today -> onSkip(t, n, today); sheet.close() }
    GlassSheet(backdrop = backdrop, onDismiss = onDismiss, state = sheet) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Skip runs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                Text(
                    "Skipped firings are logged as skipped and the routine carries on by itself after them. Pausing stops it until you turn it back on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 4.dp),
                )
                if (triggers.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = trigger == null, onClick = { trigger = null }, label = { Text("All triggers") })
                        triggers.forEach { t ->
                            FilterChip(selected = trigger == t.name, onClick = { trigger = t.name }, label = { Text(t.name) })
                        }
                    }
                }
                SkipOption("Rest of today", "Every run left today, and any retry still waiting") { pick(trigger, null, true) }
                SkipOption("Next run", nextOf(routine, trigger)) { pick(trigger, 1, false) }
                listOf(2, 3, 5).forEach { n -> SkipOption("Next $n runs", null) { pick(trigger, n, false) } }
                if (current > 0) {
                    SkipOption("Don't skip", "Undo: skipping $current now", danger = true) { pick(trigger, 0, false) }
                }
            }
    }
}

/** When the next firing of [trigger] (or of any trigger) is, for the "Next run" option. */
private fun nextOf(routine: Routine, trigger: String?): String? =
    routine.upcoming.firstOrNull { (trigger == null || it.trigger == trigger) && !it.skipped }?.let {
        (if (trigger == null && routine.cronTriggers.size > 1) "${it.trigger} · " else "") + whenText(it.at)
    }

@Composable
private fun SkipOption(label: String, detail: String?, danger: Boolean = false, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** One coming firing; skipped ones are struck through. */
@Composable
private fun UpcomingLine(u: app.krewd.data.UpcomingRun, several: Boolean) {
    val color = if (u.skipped) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            whenText(u.at) + if (several) " · ${u.trigger}" else "",
            style = MaterialTheme.typography.bodySmall.copy(
                textDecoration = if (u.skipped) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            ),
            color = color,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (u.skipped) "skipped" else untilText(u.at),
            style = MaterialTheme.typography.labelSmall,
            color = if (u.skipped) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename routine") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it.take(120) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank() && text.trim() != initial) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RoutineTopBar(title: String, showMenu: Boolean, onBack: () -> Unit, onMenu: () -> Unit) {
    // Back, title and options: one liquid glass bar, like the chat's.
    LiquidRow(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = ShellDimensions.TITLE_PILL_START_MARGIN)
            .padding(top = ShellDimensions.TITLE_PILL_TOP_GAP),
    ) {
        KrewdIconPill(onClick = onBack, icon = R.drawable.ic_chevron_left_24, contentDescription = "Back")
        KrewdPill(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = LocalContentColor.current, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (showMenu) KrewdIconPill(onClick = onMenu, icon = R.drawable.ic_more_24, contentDescription = "Routine options")
    }
}

/** The routine's options: a glass card under the top bar (plain while the glass is off), like the chat's cards. */
@Composable
private fun RoutineMenuCard(
    backdrop: io.github.zainbits.cohesion.GlassBackdrop?,
    enabled: Boolean,
    onRename: () -> Unit,
    onSkip: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val body: @Composable () -> Unit = {
        val ink = LocalContentColor.current
        Column(Modifier.padding(vertical = 8.dp)) {
            @Composable
            fun Option(label: String, hint: String?, color: androidx.compose.ui.graphics.Color = ink, onClick: () -> Unit) {
                Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
                    hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.7f)) }
                }
            }
            Option("Rename", null, onClick = onRename)
            if (enabled) Option("Skip runs…", "Today, or the next few; it carries on after", onClick = onSkip)
            Option(if (enabled) "Pause" else "Resume", if (enabled) "No runs until you turn it back on" else null, onClick = onToggle)
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = ink.copy(alpha = 0.15f))
            Option("Delete routine", null, color = MaterialTheme.colorScheme.error, onClick = onDelete)
        }
    }
    if (backdrop != null) {
        io.github.zainbits.vitreous.GlassSurface(backdrop = backdrop, modifier = modifier.width(240.dp), shape = io.github.zainbits.vitreous.GlassShape.rounded(22.dp)) { body() }
    } else {
        Surface(
            modifier = modifier.width(240.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 3.dp,
        ) { body() }
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
            run.resultReason?.takeIf { run.status == "failed" || run.status == "waiting" }?.let {
                Text(plainLine(it), style = MaterialTheme.typography.bodySmall, color = if (run.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        "failed" -> "Failed" to MaterialTheme.colorScheme.error
        "waiting" -> "Needs you" to MaterialTheme.colorScheme.tertiary
        "cancelled" -> "Stopped" to MaterialTheme.colorScheme.outline
        "skipped" -> "Skipped" to MaterialTheme.colorScheme.outline
        else -> status to MaterialTheme.colorScheme.outline
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun TriggerLine(t: RoutineTrigger, routineOn: Boolean, onUndoSkip: () -> Unit, online: Boolean) {
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
                    if (!t.enabled) "off" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (t.skipRuns > 0 && t.enabled) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.clickable(enabled = online, onClick = onUndoSkip)) {
                Row(Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Skip ${t.skipRuns}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Don't skip", tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(start = 2.dp).size(16.dp))
                }
            }
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

/**
 * Create a routine (every field), or edit one section of it ([part]): only that section is shown and saved.
 * The schedule is checked on the server as you type.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineEditor(
    state: UiState,
    viewModel: KrewdViewModel,
    existing: Routine?,
    part: EditPart = EditPart.ALL,
    onDismiss: () -> Unit,
    onSaved: (Routine) -> Unit,
) {
    val all = part == EditPart.ALL || existing == null
    val showSchedule = all || part == EditPart.SCHEDULE
    val showInstructions = all || part == EditPart.INSTRUCTIONS
    val showAgent = all || part == EditPart.AGENT
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
    var picks by remember(engineId, options) {
        mutableStateOf(
            (if (existing != null && engineId == existing.engine) existing.config else viewModel.engineDefaults(engineId).config)
                .filter { (id, value) -> options.any { o -> o.id == id && o.choices.any { it.value == value } } },
        )
    }
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    val names = drafts.map { it.name.trim().lowercase() }
    val scheduleOk = drafts.isNotEmpty() &&
        drafts.all { it.schedule.isNotBlank() && it.name.isNotBlank() && badSchedules[it.key] != true } &&
        names.toSet().size == names.size
    val valid = (!all || title.isNotBlank()) && (!showInstructions || instructions.isNotBlank()) && (!showSchedule || scheduleOk)

    val save: () -> Unit = {
        saving = true
        val fields = JSONObject()
        if (all) fields.put("title", title.trim())
        if (showSchedule) {
            fields.put("triggers", JSONArray(drafts.map {
                JSONObject().put("name", it.name.trim().lowercase()).put("schedule", it.schedule.trim())
                    .put("max_attempts", it.attempts).put("enabled", it.enabled)
            }))
        }
        if (showInstructions) fields.put("instructions", instructions.trim())
        if (showAgent) {
            fields.put("auto_approve", autoApprove)
            engineId?.let { fields.put("engine", it) }
            fields.put("config", JSONObject(picks))
        }
        if (existing != null && note.isNotBlank()) fields.put("note", note.trim())
        // A refused save shows its reason as a message, and the owner can try again.
        viewModel.saveRoutine(existing?.id, fields, failed = { saving = false }) { saving = false; onSaved(it) }
    }
    val editorTitle = when {
        existing == null -> "New routine"
        part == EditPart.SCHEDULE -> "Schedule"
        part == EditPart.INSTRUCTIONS -> "Instructions"
        part == EditPart.AGENT -> "Agent"
        else -> "Edit routine"
    }

    FullScreenPage(
        onDismiss = onDismiss,
        bar = {
            PageTopBar(
                icon = R.drawable.ic_close_24, iconLabel = "Cancel", onIcon = onDismiss, title = editorTitle,
                action = "Save", actionEnabled = valid && !saving, onAction = save,
            )
        },
    ) { contentPadding ->
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (all) OutlinedTextField(
                value = title, onValueChange = { title = it }, label = { Text("Title") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            )
            if (showSchedule) {
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
            }
            if (showInstructions) OutlinedTextField(
                value = instructions, onValueChange = { instructions = it }, label = { Text("Instructions") },
                minLines = if (all) 6 else 14, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                placeholder = { Text("What should Krewd do on each run? Say what needs your review before it submits anything.") },
            )
            if (existing != null && (showSchedule || showInstructions)) {
                OutlinedTextField(
                    value = note, onValueChange = { note = it }, label = { Text("What changed (optional, kept with the version)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                )
            }
            if (showAgent) {
            // The same controls as the new-task sheet: agents as face cards, models as cards that scroll
            // sideways, effort as a slider, modes and on/off settings as they are, auto-approve as a switch.
            SheetLabel("Who")
            CrewPicker(crewMembers(state), engineId, onPick = { engineId = it })
            if (existing != null && engineId != existing.engine) {
                Text(
                    "The routine's chat moves to this agent: the current agent writes a handover first, and switching back later resumes its own session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }
            if (options.isEmpty()) {
                Text(
                    "${state.engine(engineId)?.name ?: "This agent"} has not listed its settings yet. They appear here once it has started.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp),
                )
            }
            AgentOptions(
                options = options,
                selected = { picks[it.id] ?: it.current },
                enabled = true,
                onPick = { option, value -> picks = picks + (option.id to value) },
                autoApprove = autoApprove,
                onAutoApprove = { autoApprove = it },
            )
            Text(
                if (autoApprove) "During runs, agent prompts are answered for you." else "During runs, you OK each agent prompt (the run waits for you).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
            }
            Spacer(Modifier.padding(bottom = 24.dp))
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

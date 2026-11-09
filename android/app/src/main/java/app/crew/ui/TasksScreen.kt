package app.crew.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.crew.Connection
import app.crew.CrewViewModel
import app.crew.NEW_DRAFT
import app.crew.R
import app.crew.UiState
import app.crew.constants.ShellDimensions
import app.crew.data.ConfigOption
import app.crew.data.SessionInfo
import kotlinx.coroutines.delay

@Composable
fun TasksScreen(state: UiState, viewModel: CrewViewModel, padding: PaddingValues, onOpenSettings: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "new") { NewTaskCard(state, viewModel, onOpenSettings) }
        if (state.sessions.isEmpty() && state.connection is Connection.Online) {
            item(key = "empty") {
                Text(
                    "No tasks yet. Ask for something above: it runs on your server with its terminal and browser.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                )
            }
        }
        items(state.sessions, key = { it.id }) { session ->
            var confirm by remember { mutableStateOf(false) }
            var close by remember { mutableStateOf<() -> Unit>({}) }
            SwipeToReveal(onDelete = { closeReveal -> close = closeReveal; confirm = true }) {
                SessionRow(session, onClick = { viewModel.openSession(session.id) })
            }
            if (confirm) {
                AlertDialog(
                    onDismissRequest = { confirm = false; close() },
                    title = { Text("Delete this task?") },
                    text = { Text("\u201c${session.title}\u201d and its chat are deleted from the server. You can undo for 3 seconds.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirm = false
                            viewModel.deleteSession(session.id)
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { confirm = false; close() }) { Text("Cancel") } },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewTaskCard(state: UiState, viewModel: CrewViewModel, onOpenSettings: () -> Unit) {
    val text = state.newTaskText
    // The last agent picked here, then the server's default.
    var engineId by remember { mutableStateOf(viewModel.lastEngine()) }
    val online = state.connection is Connection.Online
    val files = state.draft(NEW_DRAFT)
    val pick = rememberFilePicker { viewModel.attach(NEW_DRAFT, it) }
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
    LaunchedEffect(online, engine?.id, options.isEmpty()) {
        // A new engine is probed in the background; ask again shortly so its choices appear.
        if (online && engine != null && options.isEmpty()) {
            repeat(5) {
                delay(4_000)
                viewModel.refreshEngines()
            }
        }
    }
    SectionCard {
        when (val c = state.connection) {
            is Connection.Offline -> Text("Can't reach Crew: ${c.reason}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Connection.NotSetUp -> TextButton(onClick = onOpenSettings) { Text("Set up the connection first") }
            else -> {}
        }
        OutlinedTextField(
            value = text,
            onValueChange = viewModel::setNewTaskText,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What should Crew do?") },
            minLines = 2,
            maxLines = 8,
            shape = RoundedCornerShape(16.dp),
        )
        PendingFiles(files, onRemove = { viewModel.removeAttachment(NEW_DRAFT, it) })
        if (engine != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip(
                    label = engine.agent?.label ?: engine.name,
                    details = listOfNotNull(engine.agent?.let { "Harness: ${it.name}" }, "Engine: ${engine.name}"),
                    choices = state.engines.map { it.id to it.name },
                    selected = engine.id,
                    onPick = {
                        engineId = it
                        viewModel.setNewTaskEngine(it)
                    },
                )
                options.forEach { option ->
                    ConfigChip(
                        option = option,
                        selected = picks[option.id] ?: option.current,
                        enabled = online,
                        onPick = { value ->
                            picks = if (value == option.current) picks - option.id else picks + (option.id to value)
                            viewModel.rememberPick(engine.id, option.id, value)
                        },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = autoApprove, onCheckedChange = { on ->
                autoApprove = on
                engine?.let { viewModel.rememberAutoApprove(it.id, on) }
            })
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Auto-approve", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (autoApprove) "Agent prompts are answered for you" else "You OK each agent prompt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = pick, enabled = online) {
                Icon(painterResource(R.drawable.ic_attach_24), contentDescription = "Attach files")
            }
            FilledIconButton(
                onClick = {
                    viewModel.newTask(text.trim(), autoApprove, engine?.id, picks)
                    viewModel.setNewTaskText("")
                },
                enabled = online && (text.isNotBlank() || files.any { it.uploadId != null }) && files.none { it.uploading },
            ) {
                Icon(painterResource(R.drawable.ic_send_24), contentDescription = "Start task")
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionInfo, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(ShellDimensions.CARD_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(shortAge(session.updatedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (session.lastText.isNotBlank()) {
                Text(
                    session.lastText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(session.status, session.pendingApprovals)
                ConfigOption.summary(session.config).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (session.autoApprove) {
                    Text("Auto-approve", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}


private val DELETE_WIDTH = 96.dp

/**
 * Slide a row left to reveal its Delete button. Releasing past half the button keeps it open; a tap on
 * the button calls [onDelete] with a function that slides the row back (for Cancel).
 */
@Composable
private fun SwipeToReveal(onDelete: (close: () -> Unit) -> Unit, content: @Composable () -> Unit) {
    val revealPx = with(androidx.compose.ui.platform.LocalDensity.current) { DELETE_WIDTH.toPx() }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { offset.animateTo(0f) } }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(ShellDimensions.CARD_CORNER)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                Modifier
                    .width(DELETE_WIDTH)
                    .fillMaxHeight()
                    .clickable(enabled = offset.value < -revealPx / 2) { onDelete(close) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(painterResource(R.drawable.ic_delete_24), contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text("Delete", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        androidx.compose.foundation.layout.Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(-revealPx * 1.3f, 0f)) }
                    },
                    onDragStopped = { velocity ->
                        offset.animateTo(if (offset.value < -revealPx / 2 || velocity < -1500f) -revealPx else 0f)
                    },
                ),
        ) { content() }
    }
}

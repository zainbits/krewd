package app.crew.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.crew.Connection
import kotlinx.coroutines.launch
import app.crew.CrewViewModel
import app.crew.R
import app.crew.UiState
import app.crew.constants.ShellDimensions
import app.crew.data.AgentCommand
import app.crew.data.ApprovalItem
import app.crew.data.ChatItem
import app.crew.data.FilesItem
import app.crew.data.NoticeItem
import app.crew.data.PendingFile
import app.crew.data.PlanItem
import app.crew.data.SwitchItem
import app.crew.data.TextItem
import app.crew.data.ToolItem
import app.crew.data.UserItem

@Composable
fun ChatScreen(state: UiState, viewModel: CrewViewModel) {
    val session = state.openSession
    // One scroll state per chat. The list is laid out bottom-up (reverseLayout), newest row first, so a chat
    // opens on its last message with no scrolling, and new output stays in view while you are at the bottom.
    val listState = remember(state.openSessionId) { LazyListState() }
    val items = state.timeline + (session?.let { viewModel.localRows(it.id) } ?: emptyList())
    val rows = items.asReversed()
    val busy = session?.isBusy == true
    // The chat agent's own commands and skills: suggested in the composer, shown as pills in messages.
    val commands = session?.let { s -> s.commands.ifEmpty { state.engine(s.engine)?.commands.orEmpty() } }.orEmpty()
    val rowCount = items.size
    val lastLength = (items.lastOrNull() as? TextItem)?.text?.length ?: 0

    // Opened from a routine's run: scroll to the message that started that run, once it is loaded.
    val focusIndex = state.chatFocus?.let { key -> rows.indexOfFirst { it.key == key }.takeIf { it >= 0 } }
    LaunchedEffect(focusIndex) {
        if (focusIndex != null) {
            // The run's message sits at the top of the screen, its reply below it.
            listState.scrollToItem(focusIndex)
            viewModel.focusDone()
        }
    }

    // Follow the newest output while you are at the bottom; leave the list alone once you scroll up to read.
    LaunchedEffect(rowCount, lastLength) {
        if (rowCount == 0 || state.chatFocus != null) return@LaunchedEffect
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }

    val bars = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues()
    var settingsOpen by rememberSaveable(state.openSessionId) { mutableStateOf(false) }
    // This chat's sign-in requests, at the bottom of the chat (also under Needs you on the Crew tab).
    val signIns = state.secrets.filter { it.pending && it.sessionId == session?.id }
    var filling by remember(state.openSessionId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Height of everything pinned at the bottom (composer, chips, keyboard), measured, so the last message
    // always scrolls clear of the composer however tall it grows.
    var bottomStack by remember { mutableStateOf(ShellDimensions.COMPOSER_MIN_HEIGHT * 2) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ShellDimensions.CONTENT_HORIZONTAL_PADDING,
                end = ShellDimensions.CONTENT_HORIZONTAL_PADDING,
                top = bars.calculateTopPadding() + ShellDimensions.TITLE_PILL_TOP_GAP +
                    ShellDimensions.TITLE_PILL_HEIGHT + ShellDimensions.CONTENT_CLEARANCE,
                bottom = bottomStack + ShellDimensions.CONTENT_CLEARANCE,
            ),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
        ) {
            items(signIns, key = { "secret-" + it.id }) { ask ->
                SecretCard(
                    ask = ask,
                    engineName = session?.let { s -> state.engine(s.engine)?.name ?: s.engine } ?: "Agent",
                    shape = RoundedCornerShape(20.dp),
                    onFill = { filling = ask.id },
                    onOpen = { filling = ask.id },
                )
            }
            items(rows, key = { it.key }) { item -> ChatRow(item, viewModel, session?.isBusy == true, commands) }
            if (state.timelineLoading && rows.isEmpty()) {
                item(key = "loading") { CircularProgressIndicator(Modifier.padding(24.dp)) }
            }
        }

        val pipTab = state.pipTab(session)
        if (session != null && pipTab != null) {
            val tab = state.browser?.tabs?.firstOrNull { it.id == pipTab } ?: session.browserTab?.takeIf { it.id == pipTab }
            BrowserPip(
                tabId = pipTab,
                label = listOfNotNull(state.engine(session.engine)?.name, tab?.host?.ifBlank { null }).joinToString(" · "),
                viewModel = viewModel,
                top = bars.calculateTopPadding() + ShellDimensions.TITLE_PILL_TOP_GAP + ShellDimensions.TITLE_PILL_HEIGHT,
                bottom = bottomStack,
                onClose = { viewModel.hidePip(session.id, pipTab) },
                onOpen = { viewModel.openBrowserAt(pipTab) },
            )
        }

        signIns.firstOrNull { it.id == filling }?.let { ask -> SecretSheet(ask = ask, viewModel = viewModel, onDismiss = { filling = null }) }
        ChatTopBar(state, viewModel, onSettings = { settingsOpen = true })
        if (settingsOpen && session != null) ChatSettingsSheet(state, viewModel, session, onDismiss = { settingsOpen = false })

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { bottomStack = with(density) { it.height.toDp() } }
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(ShellDimensions.COMPOSER_MARGIN),
        ) {
            if (session != null) {
                val files = state.draft(session.id)
                PendingFiles(files, onRemove = { viewModel.removeAttachment(session.id, it) }, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                val tabs = state.draftTabs[session.id].orEmpty()
                if (tabs.isNotEmpty()) {
                    Row(Modifier.padding(start = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tabs.forEach { tab ->
                            InputChip(
                                selected = false,
                                onClick = { viewModel.removeDraftTab(session.id, tab.id) },
                                label = { Text(tab.title.ifBlank { tab.host }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_web_24), contentDescription = null, modifier = Modifier.size(16.dp)) },
                                trailingIcon = { Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
                                colors = androidx.compose.material3.InputChipDefaults.inputChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            )
                        }
                    }
                }
                val pick = rememberFilePicker { viewModel.attach(session.id, it) }
                val live = state.live[session.id]
                Composer(
                    busy = busy,
                    steps = if (busy) live?.steps else null,
                    onSteps = { scope.launch { listState.animateScrollToItem(0) } },
                    contextUsed = session.contextUsed,
                    contextSize = session.contextSize,
                    steering = state.engine(session.engine)?.steering == true,
                    commands = commands,
                    files = files,
                    hasTabs = tabs.isNotEmpty(),
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    onAttach = pick,
                )
            }
        }
    }
}

/** Back, and the title with the agent and its settings under it; tap the title for the chat's settings. */
@Composable
private fun ChatTopBar(state: UiState, viewModel: CrewViewModel, onSettings: () -> Unit) {
    val session = state.openSession
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = ShellDimensions.TITLE_PILL_START_MARGIN)
            .padding(top = ShellDimensions.TITLE_PILL_TOP_GAP),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FloatingPill {
            IconButton(onClick = viewModel::closeSession) {
                Icon(painterResource(R.drawable.ic_chevron_left_24), contentDescription = "Back")
            }
        }
        Surface(
            onClick = onSettings,
            enabled = session != null,
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(ShellDimensions.PILL_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 2.dp,
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 7.dp)) {
                Text(
                    session?.title ?: "Task",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).background(lookFor(session.engine, state.engines).color, CircleShape))
                        Text(
                            chatSubtitle(state, session),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(painterResource(R.drawable.ic_chevron_right_24), contentDescription = "Chat settings", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** How a message goes while a turn runs: steer into it, queue after it, or stop it and send. */
private enum class Delivery(val id: String, val label: String, val hint: String) {
    Steer("steer", "Steer", "Add it to the running turn now"),
    Queue("queue", "Queue", "Run it after this turn ends"),
    Interrupt("interrupt", "Interrupt", "Stop this turn, then send"),
}

/**
 * Two rows, like the Claude app: the message on top (grows up to a few lines, then scrolls), and a row of
 * controls under it: attach and, while a turn runs, its step count on the left; how a message goes while a
 * turn runs, the context ring and send (or stop) on the right. The agent, model and settings live in the
 * title bar only.
 */
@Composable
private fun Composer(
    busy: Boolean,
    steps: Int?,
    onSteps: () -> Unit,
    contextUsed: Long?,
    contextSize: Long?,
    steering: Boolean,
    commands: List<AgentCommand>,
    files: List<PendingFile>,
    hasTabs: Boolean,
    onSend: (String, String?) -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val text = value.text
    val query = if (value.selection.collapsed) commandQuery(text, value.selection.start) else null
    val matches = remember(commands, query) { matchCommands(commands, query) }
    // The agent's own commands and skills show as pills, so they read apart from plain text.
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val ranges = remember(text, commands) { commandRanges(text, commands) }
    val ink = MaterialTheme.colorScheme.primary
    val highlight = remember(commands, ink) { CommandHighlight(commands, ink) }
    // Steer where the agent supports it, otherwise queue. Queue and interrupt work with every agent.
    var delivery by rememberSaveable(steering) { mutableStateOf(if (steering) Delivery.Steer else Delivery.Queue) }
    var deliveryMenu by remember { mutableStateOf(false) }
    val uploading = files.any { it.uploading }
    val ready = files.any { it.uploadId != null } || hasTabs
    val hasDraft = text.isNotBlank() || files.isNotEmpty() || hasTabs
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = colors.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(ShellDimensions.PILL_BORDER_WIDTH, colors.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp)) {
            if (query != null) {
                CommandSuggestions(matches, onPick = { value = applyCommand(value, query, it) }, Modifier.padding(top = 2.dp))
            }
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                visualTransformation = highlight,
                onTextLayout = { layout = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                decorationBox = { field ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                if (busy) "Steer the agent…" else "Reply to Crew…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Box(Modifier.commandPills(layout, ranges, ink.copy(alpha = 0.16f))) { field() }
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAttach) {
                    Icon(painterResource(R.drawable.ic_add_24), contentDescription = "Attach files")
                }
                if (steps != null) {
                    Spacer(Modifier.width(2.dp))
                    Surface(onClick = onSteps, shape = RoundedCornerShape(12.dp), color = colors.surfaceContainerHighest) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = colors.onSurfaceVariant)
                            Text(
                                if (steps == 1) "1 step" else "$steps steps",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (busy && hasDraft) {
                    Box {
                        Surface(onClick = { deliveryMenu = true }, shape = RoundedCornerShape(16.dp), color = colors.secondaryContainer) {
                            Text(
                                delivery.label + " ▾",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            )
                        }
                        DropdownMenu(expanded = deliveryMenu, onDismissRequest = { deliveryMenu = false }) {
                            Text(
                                "While the task is running",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                            Delivery.entries.filter { it != Delivery.Steer || steering }.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(option.label, fontWeight = if (option == delivery) FontWeight.SemiBold else FontWeight.Normal)
                                            Text(option.hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                        }
                                    },
                                    leadingIcon = { Text(if (option == delivery) "✓" else " ", color = colors.primary) },
                                    onClick = {
                                        delivery = option
                                        deliveryMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                if (contextUsed != null && contextSize != null && contextSize > 0) {
                    ContextRing(contextUsed, contextSize)
                    Spacer(Modifier.width(6.dp))
                }
                val stop = busy && !hasDraft
                val enabled = stop || ((text.isNotBlank() || ready) && !uploading)
                Surface(
                    onClick = {
                        if (stop) {
                            onStop()
                        } else {
                            onSend(text.trim(), if (busy) delivery.id else null)
                            value = TextFieldValue("")
                        }
                    },
                    enabled = enabled,
                    shape = CircleShape,
                    color = if (enabled) colors.primary else colors.surfaceContainerHighest,
                    contentColor = if (enabled) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(if (stop) R.drawable.ic_stop_24 else R.drawable.ic_send_24),
                            contentDescription = if (stop) "Stop" else if (uploading) "Uploading" else "Send",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** How full the agent's context window is: a small ring with the percentage; tap for the exact tokens. */
@Composable
private fun ContextRing(used: Long, size: Long) {
    var open by remember { mutableStateOf(false) }
    val fraction = (used.toFloat() / size).coerceIn(0f, 1f)
    val percent = (fraction * 100).toInt()
    val colors = MaterialTheme.colorScheme
    val tone = when {
        fraction >= 0.9f -> colors.error
        fraction >= 0.7f -> app.crew.LocalCrewColors.current.needs
        else -> colors.primary
    }
    Box {
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(28.dp),
                color = tone,
                trackColor = colors.surfaceContainerHighest,
                strokeWidth = 2.5.dp,
                gapSize = 0.dp,
                strokeCap = StrokeCap.Round,
            )
            Text(
                "$percent",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 9.sp),
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = colors.surfaceContainerHighest,
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp).widthIn(min = 220.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Context", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("$percent%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tone)
                }
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = tone,
                    trackColor = colors.surfaceContainer,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                TokenLine("Used", used)
                TokenLine("Remaining", (size - used).coerceAtLeast(0))
                HorizontalDivider(color = colors.outlineVariant)
                TokenLine("Window", size)
            }
        }
    }
}

@Composable
private fun TokenLine(label: String, tokens: Long) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            "%,d".format(tokens),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun ChatRow(item: ChatItem, viewModel: CrewViewModel, busy: Boolean, commands: List<AgentCommand>) {
    when (item) {
        is UserItem -> when (item.purpose) {
            "routine" -> RoutineRunRow(item)
            "compact" -> CompactRow(item)
            "switch" -> SwitchPendingRow(item, viewModel.state.value.engine(item.switchTo)?.name ?: item.switchTo ?: "another agent")
            else -> UserRow(item, busy, commands, onResend = { item.clientId?.let(viewModel::resend) })
        }
        is TextItem -> if (item.isThought) ThoughtRow(item.text) else SelectionContainer { Markdown(item.text) }
        is ToolItem -> ToolRow(item, viewModel::toolImage)
        is PlanItem -> SectionCard {
            Text("Plan", style = MaterialTheme.typography.labelLarge)
            item.entries.forEach { entry ->
                val mark = when (entry.status) {
                    "completed" -> "✓"
                    "in_progress" -> "→"
                    else -> "○"
                }
                Text("$mark  ${entry.content}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        is ApprovalItem -> ApprovalCard(item.approval, taskTitle = null, onDecide = { viewModel.decide(item.approval, it) })
        is FilesItem -> {
            val downloading by viewModel.downloading.collectAsStateWithLifecycle()
            HandedFiles(item.files, downloading, viewModel::openSharedFile)
        }
        is SwitchItem -> SwitchRow(item)
        is NoticeItem -> Text(
            item.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun UserRow(item: UserItem, busy: Boolean, commands: List<AgentCommand>, onResend: () -> Unit) {
    val failed = item.state == "failed"
    val onBubble = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp).then(if (failed) Modifier.clickable(onClick = onResend) else Modifier),
        ) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.tabs.forEach { tab ->
                    Row(
                        Modifier.background(Color(0x22000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_web_24), contentDescription = null, tint = onBubble, modifier = Modifier.size(14.dp))
                        Text(tab.title.ifBlank { tab.host }, style = MaterialTheme.typography.labelMedium, color = onBubble, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (item.text.isNotBlank()) {
                    val ranges = remember(item.text, commands) { commandRanges(item.text, commands) }
                    var laid by remember { mutableStateOf<TextLayoutResult?>(null) }
                    SelectionContainer {
                        Text(
                            highlightCommands(item.text, ranges, onBubble),
                            color = onBubble,
                            onTextLayout = { laid = it },
                            modifier = Modifier.commandPills(laid, ranges, onBubble.copy(alpha = 0.13f)),
                        )
                    }
                }
                if (item.attachments.isNotEmpty()) SentFiles(item.attachments, onBubble)
                DeliveryLine(item, busy, onBubble)
            }
        }
    }
}

/** A routine's run, as Crew sent it: a header with the full prompt behind a tap. */
@Composable
private fun RoutineRunRow(item: UserItem) {
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().clickable { open = !open },
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_schedule_24), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                Text(
                    listOfNotNull(
                        if (item.trigger == "manual") "Run now" else if (item.attempt > 1) "Retry" else "Scheduled run",
                        item.triggers.filter { it != "main" }.takeIf { it.isNotEmpty() }?.joinToString(", "),
                        if (item.maxAttempts > 1) "attempt ${item.attempt} of ${item.maxAttempts}" else null,
                        item.version?.let { "v$it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when (item.state) {
                        "queued", "steering" -> "Queued"
                        "cancelled" -> "Not run"
                        else -> if (open) "Hide" else "Details"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                )
            }
            if (open) SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
        }
    }
}

/** The chat is moving to another agent (after the current turn). */
@Composable
private fun SwitchPendingRow(item: UserItem, toName: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_swap_24), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            when (item.state) {
                "queued", "steering" -> "Moving to $toName after this turn"
                "cancelled" -> "Move to $toName cancelled (stopped)"
                else -> "Handing over to $toName"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The chat moved to another agent: a divider, with the handover the previous agent wrote behind a tap. */
@Composable
private fun SwitchRow(item: SwitchItem) {
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(enabled = item.summary != null) { open = !open },
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_swap_24), contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Now on ${item.toName}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(
                    if (item.summary == null) "from ${item.fromName}" else if (open) "Hide handover" else "Handover",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (open && item.summary != null) SelectionContainer { Markdown(item.summary) }
        }
    }
}

/** "Compacting the context", sent from the chat menu. */
@Composable
private fun CompactRow(item: UserItem) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_compress_24), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            when (item.state) {
                "queued", "steering" -> "Compact queued: after this turn"
                "cancelled" -> "Compact not run (stopped)"
                else -> "Compacting the context"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One tick: stored on the server. Two ticks: the agent has it (steered in, or its turn started). */
@Composable
private fun ColumnScope.DeliveryLine(item: UserItem, busy: Boolean, color: Color) {
    val (ticks, label) = when (item.state) {
        "sending" -> "" to "Sending…"
        "failed" -> "" to "Not sent · tap to resend"
        "cancelled" -> "" to "Not run (stopped)"
        "delivered" -> "✓✓" to if (item.how == "steered") "Steered" else ""
        "queued", "steering" -> "✓" to if (item.delivery == "queue" || busy) "Queued" else ""
        else -> "" to ""
    }
    if (ticks.isEmpty() && label.isEmpty()) return
    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.75f))
        if (ticks.isNotEmpty()) {
            Text(
                ticks,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (ticks == "✓✓") MaterialTheme.colorScheme.primary else color.copy(alpha = 0.7f),
                letterSpacing = (-2).sp,
            )
        }
    }
}

@Composable
private fun ThoughtRow(text: String) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable { open = !open }.animateContentSize().padding(horizontal = 4.dp),
    ) {
        Text(
            if (open) text else "Thinking: " + text.lineSequence().firstOrNull().orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (open) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToolRow(item: ToolItem, loadImage: suspend (String) -> ImageBitmap?) {
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable { open = !open }.animateContentSize(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolStatus(item.status)
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (open) 6 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (open) {
                item.input?.let { MonoBlock(it, maxLines = 30) }
                item.output?.let { MonoBlock(it, maxLines = 60) }
                item.images.forEach { ToolImage(it, loadImage) }
            }
        }
    }
}

/** A picture from a tool result, full card width. */
@Composable
private fun ToolImage(name: String, load: suspend (String) -> ImageBitmap?) {
    var failed by remember(name) { mutableStateOf<String?>(null) }
    val bitmap by produceState<ImageBitmap?>(null, name) {
        value = try {
            load(name) ?: run { failed = "Not connected to Crew"; null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.message ?: "Could not load the image"
            null
        }
    }
    val shape = RoundedCornerShape(10.dp)
    val image = bitmap
    if (image != null) {
        Image(
            image,
            contentDescription = "Image from the tool result",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1)).clip(shape),
        )
    } else {
        Box(
            Modifier.fillMaxWidth().height(96.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val error = failed
            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun ToolStatus(status: String?) {
    val size = 16.dp
    when (status) {
        "completed" -> Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        "failed" -> Text("✕", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
        else -> CircularProgressIndicator(Modifier.size(size), strokeWidth = 2.dp)
    }
}



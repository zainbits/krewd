package app.crew.ui

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.InputChip
import app.crew.Connection
import app.crew.CrewViewModel
import app.crew.R
import app.crew.UiState
import app.crew.constants.ShellDimensions
import app.crew.data.ApprovalItem
import app.crew.data.ChatItem
import app.crew.data.NoticeItem
import app.crew.data.PendingFile
import app.crew.data.PlanItem
import app.crew.data.TextItem
import app.crew.data.ToolItem
import app.crew.data.UserItem

@Composable
fun ChatScreen(state: UiState, viewModel: CrewViewModel) {
    val session = state.openSession
    val listState = rememberLazyListState()
    val items = state.timeline + (session?.let { viewModel.localRows(it.id) } ?: emptyList())
    val busy = session?.isBusy == true
    val rowCount = items.size + if (busy) 1 else 0
    val lastLength = (items.lastOrNull() as? TextItem)?.text?.length ?: 0

    // Follow the newest output unless the user has scrolled up to read.
    LaunchedEffect(rowCount, lastLength) {
        if (rowCount == 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 3) {
            listState.animateScrollToItem(rowCount - 1)
        }
    }

    val bars = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues()
    val bottomInsets = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ShellDimensions.CONTENT_HORIZONTAL_PADDING,
                end = ShellDimensions.CONTENT_HORIZONTAL_PADDING,
                top = bars.calculateTopPadding() + ShellDimensions.TITLE_PILL_TOP_GAP +
                    ShellDimensions.TITLE_PILL_HEIGHT + ShellDimensions.CONTENT_CLEARANCE,
                bottom = bottomInsets.calculateBottomPadding() + ShellDimensions.COMPOSER_MIN_HEIGHT +
                    ShellDimensions.COMPOSER_MARGIN * 2 + ShellDimensions.AGENT_CHIPS_HEIGHT + ShellDimensions.CONTENT_CLEARANCE +
                    // room for the "Add to chat" tab chips above the composer
                    (if (session != null && state.draftTabs[session.id].orEmpty().isNotEmpty()) ShellDimensions.AGENT_CHIPS_HEIGHT else 0.dp),
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.timelineLoading) {
                item(key = "loading") { CircularProgressIndicator(Modifier.padding(24.dp)) }
            }
            items(items, key = { it.key }) { item -> ChatRow(item, viewModel, session?.isBusy == true) }
            if (busy) {
                item(key = "working") { WorkingRow(waiting = session?.status == "waiting") }
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
                bottom = bottomInsets.calculateBottomPadding() + ShellDimensions.COMPOSER_MIN_HEIGHT +
                    ShellDimensions.COMPOSER_MARGIN * 2 + ShellDimensions.AGENT_CHIPS_HEIGHT,
                onClose = { viewModel.hidePip(session.id, pipTab) },
                onOpen = { viewModel.openBrowserAt(pipTab) },
            )
        }

        ChatTopBar(state, viewModel)

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(ShellDimensions.COMPOSER_MARGIN),
        ) {
            if (session != null) {
                SessionAgentChips(
                    session = session,
                    engineName = state.engines.firstOrNull { it.id == session.engine }?.name ?: session.engine,
                    enabled = state.connection is Connection.Online,
                    onPick = { configId, value -> viewModel.setConfig(session.id, configId, value) },
                    onMode = { viewModel.setMode(session.id, it) },
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
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
                Composer(
                    busy = busy,
                    steering = state.engine(session.engine)?.steering == true,
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

@Composable
private fun ChatTopBar(state: UiState, viewModel: CrewViewModel) {
    val session = state.openSession
    var menuOpen by remember { mutableStateOf(false) }
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
        FloatingPill(Modifier.weight(1f)) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    session?.title ?: "Task",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (session != null) StatusChip(session.status, session.pendingApprovals)
            }
        }
        Box {
            FloatingPill {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_more_24), contentDescription = "Task options")
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (session != null) {
                    DropdownMenuItem(
                        text = { Text(if (session.autoApprove) "Auto-approve: on" else "Auto-approve: off") },
                        onClick = {
                            viewModel.setAutoApprove(session.id, !session.autoApprove)
                            menuOpen = false
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Watch the browser") }, onClick = {
                        menuOpen = false
                        viewModel.openBrowserAt(session.browserTab?.id)
                    })
                    DropdownMenuItem(text = { Text("Archive task") }, onClick = {
                        menuOpen = false
                        viewModel.archive(session.id)
                    })
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

@Composable
private fun Composer(
    busy: Boolean,
    steering: Boolean,
    files: List<PendingFile>,
    hasTabs: Boolean,
    onSend: (String, String?) -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    // Steer where the agent supports it, otherwise queue. Queue and interrupt work with every agent.
    var delivery by rememberSaveable(steering) { mutableStateOf(if (steering) Delivery.Steer else Delivery.Queue) }
    var deliveryMenu by remember { mutableStateOf(false) }
    val uploading = files.any { it.uploading }
    val ready = files.any { it.uploadId != null } || hasTabs
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(ShellDimensions.PILL_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)) {
            IconButton(onClick = onAttach) {
                Icon(painterResource(R.drawable.ic_attach_24), contentDescription = "Attach files")
            }
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(if (busy) "Add to the task…" else "Reply…") },
                modifier = Modifier.weight(1f),
                maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            if (busy) {
                Box(Modifier.align(Alignment.CenterVertically)) {
                    Surface(
                        onClick = { deliveryMenu = true },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            delivery.label + " ▾",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                    DropdownMenu(expanded = deliveryMenu, onDismissRequest = { deliveryMenu = false }) {
                        Text(
                            "While the task is running",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        Delivery.entries.filter { it != Delivery.Steer || steering }.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(option.label, fontWeight = if (option == delivery) FontWeight.SemiBold else FontWeight.Normal)
                                        Text(option.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                leadingIcon = { Text(if (option == delivery) "✓" else " ", color = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    delivery = option
                                    deliveryMenu = false
                                },
                            )
                        }
                    }
                }
            }
            if (busy && text.isBlank() && files.isEmpty() && !hasTabs) {
                IconButton(onClick = onStop) {
                    Icon(painterResource(R.drawable.ic_stop_24), contentDescription = "Stop")
                }
            } else {
                IconButton(
                    onClick = {
                        onSend(text.trim(), if (busy) delivery.id else null)
                        text = ""
                    },
                    enabled = (text.isNotBlank() || ready) && !uploading,
                ) {
                    Icon(painterResource(R.drawable.ic_send_24), contentDescription = if (uploading) "Uploading" else "Send")
                }
            }
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem, viewModel: CrewViewModel, busy: Boolean) {
    when (item) {
        is UserItem -> UserRow(item, busy, onResend = { item.clientId?.let(viewModel::resend) })
        is TextItem -> if (item.isThought) ThoughtRow(item.text) else SelectionContainer { Markdown(item.text) }
        is ToolItem -> ToolRow(item)
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
        is NoticeItem -> Text(
            item.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun UserRow(item: UserItem, busy: Boolean, onResend: () -> Unit) {
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
                    SelectionContainer { Text(item.text, color = onBubble) }
                }
                if (item.attachments.isNotEmpty()) SentFiles(item.attachments, onBubble)
                DeliveryLine(item, busy, onBubble)
            }
        }
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
private fun ToolRow(item: ToolItem) {
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

@Composable
private fun WorkingRow(waiting: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(4.dp)) {
        if (!waiting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            if (waiting) "Waiting for your OK" else "Working…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


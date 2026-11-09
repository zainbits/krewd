package app.crew.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import app.crew.Connection
import app.crew.CrewViewModel
import app.crew.R
import app.crew.UiState
import app.crew.constants.ShellDimensions
import app.crew.data.ApprovalItem
import app.crew.data.ChatItem
import app.crew.data.NoticeItem
import app.crew.data.PlanItem
import app.crew.data.TextItem
import app.crew.data.ToolItem
import app.crew.data.UserItem

@Composable
fun ChatScreen(state: UiState, viewModel: CrewViewModel) {
    val session = state.openSession
    val listState = rememberLazyListState()
    val items = state.timeline
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
                    ShellDimensions.COMPOSER_MARGIN * 2 + ShellDimensions.AGENT_CHIPS_HEIGHT + ShellDimensions.CONTENT_CLEARANCE,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.timelineLoading) {
                item(key = "loading") { CircularProgressIndicator(Modifier.padding(24.dp)) }
            }
            items(items, key = { it.key }) { item -> ChatRow(item, viewModel) }
            if (busy) {
                item(key = "working") { WorkingRow(waiting = session?.status == "waiting") }
            }
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
            Composer(busy = busy, onSend = viewModel::send, onStop = viewModel::stop)
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
                        viewModel.openTab()
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

@Composable
private fun Composer(busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(ShellDimensions.PILL_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)) {
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
            if (busy && text.isBlank()) {
                IconButton(onClick = onStop) {
                    Icon(painterResource(R.drawable.ic_stop_24), contentDescription = "Stop")
                }
            } else {
                IconButton(
                    onClick = {
                        onSend(text.trim())
                        text = ""
                    },
                    enabled = text.isNotBlank(),
                ) {
                    Icon(painterResource(R.drawable.ic_send_24), contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem, viewModel: CrewViewModel) {
    when (item) {
        is UserItem -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                SelectionContainer {
                    Text(item.text, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        is TextItem -> if (item.isThought) ThoughtRow(item.text) else SelectionContainer { RichText(item.text) }
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

/** Light Markdown: fenced code blocks, inline `code` and **bold**. Enough for agent replies. */
@Composable
private fun RichText(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        text.split("```").forEachIndexed { i, part ->
            if (i % 2 == 1) {
                val code = part.substringAfter('\n', part).trimEnd()
                MonoBlock(code)
            } else if (part.isNotBlank()) {
                Text(inlineMarkdown(part.trim('\n')), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (match in INLINE.findAll(text)) {
        append(text.substring(at, match.range.first))
        val bold = match.groupValues[1]
        if (bold.isNotEmpty()) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
        } else {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22888888))) { append(match.groupValues[2]) }
        }
        at = match.range.last + 1
    }
    append(text.substring(at))
}

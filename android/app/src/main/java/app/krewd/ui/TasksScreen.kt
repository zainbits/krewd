package app.krewd.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.krewd.Connection
import app.krewd.KrewdType
import app.krewd.KrewdViewModel
import app.krewd.LiveInfo
import app.krewd.LocalKrewdColors
import app.krewd.R
import app.krewd.UiState
import app.krewd.constants.ShellDimensions
import app.krewd.data.Approval
import app.krewd.data.Routine
import app.krewd.data.SessionInfo
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import io.github.zainbits.meniscus.MeniscusNavBarDefaults

/** Height of the floating "Ask Krewd" bar and its gap above the nav bar. */
val ASK_BAR_HEIGHT = 58.dp
val ASK_BAR_GAP = 10.dp

/** Every agent with its face and a short note, for the roster and the pickers. */
fun crewMembers(state: UiState): List<CrewMember> = state.engines.map { engine ->
    val face = faceOfEngine(engine.id, state.sessions, state.live)
    val running = state.sessions.count { it.engine == engine.id && it.status == "running" }
    val note = when {
        face == FaceState.NeedsYou -> "Needs you"
        running == 1 -> "1 running"
        running > 1 -> "$running running"
        else -> "Idle"
    }
    CrewMember(engine, lookFor(engine.id, state.engines), face, note)
}

/**
 * The Krewd tab: who needs you first, then what is running (with what each agent is doing), what runs next,
 * and the rest of the tasks. New tasks start from the "Ask Krewd" bar or by tapping an agent's face.
 */
@Composable
fun TasksScreen(
    state: UiState,
    viewModel: KrewdViewModel,
    padding: PaddingValues,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenSettings: () -> Unit,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    var askEngine by rememberSaveable { mutableStateOf<String?>(null) }
    var showEarlier by rememberSaveable { mutableStateOf(false) }
    val ask: (String?) -> Unit = { askEngine = it; asking = true }

    val sessions = state.sessions.filter { !it.archived }
    val pending = state.approvals.filter { it.pending }
    val signIns = state.secrets.filter { it.pending }
    val proposals = state.proposals.filter { it.pending }
    var filling by remember { mutableStateOf<String?>(null) }
    var reviewing by rememberSaveable { mutableStateOf<String?>(null) }
    val running = sessions.filter { it.status == "running" || it.status == "waiting" }
        .filter { s -> pending.none { it.sessionId == s.id } }
    val rest = sessions.filter { s -> s !in running && pending.none { it.sessionId == s.id } }
    val pinned = rest.filter { it.pinned }
    val today = LocalDate.now()
    val todays = rest.filter { !it.pinned && localDate(it.updatedAt) == today }
    val earlier = rest.filter { !it.pinned && localDate(it.updatedAt) != today }
    val upcoming = state.routines.filter { it.enabled && it.nextRunAt != null }.sortedBy { it.nextRunAt }.take(4)
    val members = crewMembers(state)

    signIns.firstOrNull { it.id == filling }?.let { ask ->
        SecretSheet(ask = ask, viewModel = viewModel, onDismiss = { filling = null })
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item(key = "header") { HomeHeader(state.connection, onOpenSettings) }
            item(key = "hero") {
                Hero(
                    needs = pending.size + signIns.size + proposals.size,
                    working = running.size,
                    next = upcoming.firstOrNull(),
                )
            }
            if (members.isNotEmpty()) item(key = "roster") {
                Roster(members, needsOf = { id -> pending.count { a -> sessions.firstOrNull { it.id == a.sessionId }?.engine == id } }, onTap = ask)
            }
            val needs = proposals.size + signIns.size + pending.size
            if (needs > 0) item(key = "needs-title") { SectionTitle("Needs you", needs.toString()) }
            if (proposals.isNotEmpty()) {
                items(proposals.size, key = { "proposal-" + proposals[it].id }) { i ->
                    val proposal = proposals[i]
                    val from = state.session(proposal.sessionId)
                    ProposalCard(
                        proposal = proposal,
                        chatTitle = from?.title ?: proposal.sessionTitle,
                        look = lookFor(from?.engine, state.engines),
                        onReview = { reviewing = proposal.id },
                        onDismiss = { viewModel.dismissProposal(proposal) },
                        onOpenResult = null,
                        shape = groupShape(i, proposals.size),
                    )
                }
            }
            if (signIns.isNotEmpty()) {
                items(signIns.size, key = { "secret-" + signIns[it].id }) { i ->
                    val ask = signIns[i]
                    SecretCard(
                        ask = ask,
                        engineName = sessions.firstOrNull { it.id == ask.sessionId }?.let { s -> state.engine(s.engine)?.name ?: s.engine } ?: "Agent",
                        shape = groupShape(i, signIns.size + pending.size),
                        onFill = { filling = ask.id },
                        onOpen = { viewModel.openSession(ask.sessionId) },
                    )
                }
            }
            if (pending.isNotEmpty()) {
                items(pending.size, key = { "needs-" + pending[it].id }) { i ->
                    val approval = pending[i]
                    val session = sessions.firstOrNull { it.id == approval.sessionId }
                    NeedsCard(
                        approval = approval,
                        session = session,
                        look = lookFor(session?.engine, state.engines),
                        engineName = state.engine(session?.engine)?.name ?: session?.engine ?: "Agent",
                        shape = groupShape(i, pending.size),
                        onDecide = { viewModel.decide(approval, it) },
                        onOpen = { viewModel.openSession(approval.sessionId) },
                    )
                }
            }
            if (running.isNotEmpty()) {
                item(key = "working-title") { SectionTitle("Working", running.size.toString()) }
                items(running.size, key = { "run-" + running[it].id }) { i ->
                    val session = running[i]
                    WorkingCard(
                        session = session,
                        live = state.live[session.id],
                        look = lookFor(session.engine, state.engines),
                        engineName = state.engine(session.engine)?.name ?: session.engine,
                        shape = groupShape(i, running.size),
                        viewModel = viewModel,
                    )
                }
            }
            if (upcoming.isNotEmpty()) {
                item(key = "soon-title") { SectionTitle("Coming up", null) }
                item(key = "soon") { ComingUp(upcoming, state, onOpen = viewModel::openRoutine) }
            }
            if (sessions.isEmpty() && state.connection is Connection.Online) {
                item(key = "empty") {
                    Text(
                        "No tasks yet. Tap Ask Krewd below: the task runs on your server with its terminal and browser.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 20.dp),
                    )
                }
            }
            listOf("Pinned" to pinned, "Today" to todays).forEach { (title, list) ->
                if (list.isNotEmpty()) {
                    item(key = "title-$title") { SectionTitle(title, null) }
                    items(list, key = { "row-" + it.id }) { TaskRowItem(it, state, viewModel) }
                }
            }
            if (earlier.isNotEmpty()) {
                if (showEarlier) {
                    item(key = "title-Earlier") { SectionTitle("Earlier", earlier.size.toString()) }
                    items(earlier, key = { "row-" + it.id }) { TaskRowItem(it, state, viewModel) }
                } else {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { showEarlier = true }) {
                                Text(if (earlier.size == 1) "1 earlier task" else "${earlier.size} earlier tasks")
                            }
                        }
                    }
                }
            }
        }

        // The Ask Krewd bar floats above the nav bar; rows scroll full bleed behind both.
        val navRoom = MeniscusNavBarDefaults.BottomGap + MeniscusNavBarDefaults.Height
        // Transparent shield over the dock: taps on the gaps around the bar and nav bar must not reach rows behind;
        // drags still pass through, so the list scrolls from there.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(navRoom + ASK_BAR_GAP + ASK_BAR_HEIGHT)
                .pointerInput(Unit) { detectTapGestures { } },
        )
        AskBar(
            text = state.newTaskText,
            look = lookFor(viewModel.lastEngine() ?: state.engines.firstOrNull { it.isDefault }?.id, state.engines),
            onClick = { ask(null) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = MeniscusNavBarDefaults.SideMargin, end = MeniscusNavBarDefaults.SideMargin, bottom = navRoom + ASK_BAR_GAP),
        )
    }
    }

    if (asking) NewTaskSheet(state, viewModel, askEngine, onDismiss = { asking = false }, onOpenSettings = onOpenSettings)
    reviewing?.let { id ->
        val proposal = state.proposals.firstOrNull { it.id == id }
        if (proposal == null) {
            LaunchedEffect(id) { reviewing = null }
        } else {
            NewTaskSheet(state, viewModel, proposal.engine ?: state.session(proposal.sessionId)?.engine, onDismiss = { reviewing = null },
                onOpenSettings = onOpenSettings, proposal = proposal)
        }
    }
}

private fun localDate(iso: String): LocalDate? =
    runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }.getOrNull()

/** Rounded at the ends of a group, tight in the middle (Material 3 Expressive lists). */
private fun groupShape(i: Int, n: Int): RoundedCornerShape {
    val top = if (i == 0) 28.dp else 8.dp
    val bottom = if (i == n - 1) 28.dp else 8.dp
    return RoundedCornerShape(top, top, bottom, bottom)
}

@Composable
private fun HomeHeader(connection: Connection, onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Krewd", fontFamily = KrewdType.wordmark, fontSize = 22.sp, modifier = Modifier.weight(1f).semantics { heading() })
        val (dot, label) = when (connection) {
            is Connection.Online -> LocalKrewdColors.current.ok to "Online · ${connection.route}"
            Connection.Connecting -> MaterialTheme.colorScheme.outline to "Connecting"
            Connection.NotSetUp -> MaterialTheme.colorScheme.outline to "Set up"
            is Connection.Offline -> MaterialTheme.colorScheme.error to "Offline"
        }
        Surface(
            onClick = onOpenSettings,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (connection is Connection.Offline) {
        Text(
            "Can't reach Krewd: ${connection.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 6.dp, top = 6.dp),
        )
    }
}

/** The headline: how many need you, or how many are working, or all clear. */
@Composable
private fun Hero(needs: Int, working: Int, next: Routine?) {
    val krewd = LocalKrewdColors.current
    val headline = buildAnnotatedString {
        when {
            needs > 0 -> { withStyle(SpanStyle(color = krewd.needs)) { append("$needs") }; append(" need you") }
            working > 0 -> { withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("$working") }; append(" working") }
            else -> append("All clear")
        }
    }
    val sub = listOfNotNull(
        if (needs > 0 && working > 0) "$working working" else null,
        if (needs == 0 && working > 0) "Nothing needs you" else null,
        if (needs == 0 && working == 0) "Nothing running" else null,
        next?.let { "${it.title.lowercase().replaceFirstChar { c -> c.uppercase() }} at ${shortTime(it.nextRunAt)}" },
    ).joinToString(" · ")
    Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 22.dp, bottom = 4.dp)) {
        Text(
            headline,
            fontFamily = KrewdType.hero,
            lineHeight = 54.sp,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 32.sp, maxFontSize = 56.sp),
            modifier = Modifier.semantics { heading() },
        )
        if (sub.isNotBlank()) {
            Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** The krewd: each agent's face acts out what it is doing. Tap a face to give that agent a task. */
@Composable
private fun Roster(members: List<CrewMember>, needsOf: (String) -> Int, onTap: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 18.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        members.forEach { m ->
            val needs = needsOf(m.engine.id)
            Column(
                Modifier.width(98.dp).clip(RoundedCornerShape(20.dp)).clickable { onTap(m.engine.id) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    CrewFace(m.look, m.state, 62.dp, description = "${m.engine.name}, ${m.state.label}. Tap to give it a task.")
                    if (needs > 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-2).dp).size(22.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp)
                                .background(LocalKrewdColors.current.needs, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$needs", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = LocalKrewdColors.current.onNeeds)
                        }
                    }
                }
                Text(m.engine.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(
                    if (m.state == FaceState.NeedsYou) "Needs you" else m.state.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (m.state == FaceState.NeedsYou) LocalKrewdColors.current.needs else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, count: String?) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 22.dp, bottom = 7.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).semantics { heading() })
        if (count != null) Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
    }
}

/** A permission prompt from an agent, answered right here with the agent's own choices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeedsCard(
    approval: Approval,
    session: SessionInfo?,
    look: KrewdLook,
    engineName: String,
    shape: RoundedCornerShape,
    onDecide: (String) -> Unit,
    onOpen: () -> Unit,
) {
    val krewd = LocalKrewdColors.current
    Surface(onClick = onOpen, shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 18.dp, end = 16.dp, top = 15.dp, bottom = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrewFace(look, FaceState.NeedsYou, 22.dp)
                Text(
                    listOfNotNull(engineName, session?.title).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                session?.let { Text(shortAge(it.updatedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline) }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(approval.title, style = MaterialTheme.typography.bodyLarge)
                    approval.input?.takeIf { it.isNotBlank() && it != approval.title }?.let {
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = krewd.needs, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Deny-type choices first and quiet, the allow choices last and bright.
                approval.options.sortedBy { it.allows }.forEach { option ->
                    if (option.allows) {
                        Button(
                            onClick = { onDecide(option.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = krewd.needs, contentColor = krewd.onNeeds),
                        ) { Text(option.name, fontWeight = FontWeight.SemiBold) }
                    } else {
                        FilledTonalButton(onClick = { onDecide(option.id) }) { Text(option.name) }
                    }
                }
            }
        }
    }
}

/** A running task: the agent's face and what it is doing now, the time and steps so far, and its browser tab. */
@Composable
private fun WorkingCard(session: SessionInfo, live: LiveInfo?, look: KrewdLook, engineName: String, shape: RoundedCornerShape, viewModel: KrewdViewModel) {
    val face = faceOf(session, live)
    SwipeToPinOrDelete(session, viewModel) {
        Surface(onClick = { viewModel.openSession(session.id) }, shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CrewFace(look, face, 22.dp)
                        Text(
                            live?.activity ?: "Working",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (session.pinned) {
                            Icon(painterResource(R.drawable.ic_pin_24), contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                        }
                        Text(session.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        session.sideLabel?.let { SideTag(it) }
                        if (session.waitingOn.isNotEmpty()) SideTag("waiting on ${session.waitingOn.size}")
                    }
                    session.parentId?.let { pid ->
                        val parent = viewModel.state.value.session(pid)
                        ParentLine(parent?.title ?: session.parentTitle ?: "Parent chat", parent?.let { p -> { viewModel.openSession(p.id) } })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(engineName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        if (live != null) {
                            Text("·", color = MaterialTheme.colorScheme.outline)
                            Elapsed(live.since)
                            if (live.steps > 0) {
                                Text("·", color = MaterialTheme.colorScheme.outline)
                                Text(if (live.steps == 1) "1 step" else "${live.steps} steps", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
                session.browserTab?.id?.takeIf { it.isNotEmpty() }?.let { tabId ->
                    Spacer(Modifier.width(14.dp))
                    TabStill(tabId, session.browserTab?.host ?: "", viewModel, Modifier.size(width = 96.dp, height = 120.dp))
                }
            }
        }
    }
}

/** Minutes and seconds since [since], ticking. */
@Composable
fun Elapsed(since: Long, color: Color = MaterialTheme.colorScheme.outline) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val s = ((now - since) / 1000).coerceAtLeast(0)
    Text(
        if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60),
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = color,
    )
}

/** A still of the agent's browser tab, refreshed every few seconds while the card is on screen. */
@Composable
private fun TabStill(tabId: String, host: String, viewModel: KrewdViewModel, modifier: Modifier = Modifier) {
    var still by remember(tabId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(tabId) {
        while (true) {
            viewModel.tabSnapshot(tabId)?.let { still = it }
            delay(4_000)
        }
    }
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val image = still
        if (image != null) {
            Image(image, contentDescription = "The agent's tab: $host", contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                host.ifBlank { "Browser" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
        }
        Row(
            Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(6.dp).background(Color(0xFFFF5A5A), CircleShape))
            Text("LIVE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ComingUp(routines: List<Routine>, state: UiState, onOpen: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        routines.forEach { r ->
            Surface(onClick = { onOpen(r.id) }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.widthIn(min = 160.dp, max = 220.dp).padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(shortTime(r.nextRunAt), fontFamily = KrewdType.number, fontSize = 26.sp)
                    Text(r.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    Text(
                        listOfNotNull(state.engine(r.engine)?.name, dayOf(r.nextRunAt)).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

private fun zoned(iso: String?) = iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull() }

/** "8:30" for a time. */
private fun shortTime(iso: String?): String = zoned(iso)?.format(DateTimeFormatter.ofPattern("H:mm")) ?: ""

/** "today", "tomorrow" or "Mon 17 Nov". */
private fun dayOf(iso: String?): String? {
    val t = zoned(iso) ?: return null
    val today = LocalDate.now()
    return when (t.toLocalDate()) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> t.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
}

@Composable
private fun AskBar(text: String, look: KrewdLook, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().height(ASK_BAR_HEIGHT),
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CrewFace(look, FaceState.Idle, 38.dp, description = null)
            Text(
                text.ifBlank { "Ask Krewd…" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A finished or idle task, with swipe left for Pin and Delete. */
@Composable
private fun TaskRowItem(session: SessionInfo, state: UiState, viewModel: KrewdViewModel) {
    Box(Modifier.padding(vertical = 2.dp)) {
        SwipeToPinOrDelete(session, viewModel) {
            TaskRow(
                session, lookFor(session.engine, state.engines), onClick = { viewModel.openSession(session.id) },
                parentTitle = session.parentId?.let { state.session(it)?.title ?: session.parentTitle },
                onParent = session.parentId?.takeIf { state.session(it) != null }?.let { id -> { viewModel.openSession(id) } },
            )
        }
    }
}

/**
 * Swipe left for Pin and Delete, on idle rows and on working cards alike. A working task pinned now stays in
 * Working while it runs and shows under Pinned once it stops; deleting one stops its agent first.
 */
@Composable
private fun SwipeToPinOrDelete(session: SessionInfo, viewModel: KrewdViewModel, content: @Composable () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    var close by remember { mutableStateOf<() -> Unit>({}) }
    val working = session.status == "running" || session.status == "waiting"
    LiquidSwipeReveal(
        pinned = session.pinned,
        onPin = { closeReveal -> closeReveal(); viewModel.setPinned(session.id, !session.pinned) },
        onDelete = { closeReveal -> close = closeReveal; confirm = true },
        pinIcon = R.drawable.ic_pin_24,
        deleteIcon = R.drawable.ic_delete_24,
        content = content,
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false; close() },
            title = { Text("Delete this task?") },
            text = {
                Text(
                    (if (working) "The agent is stopped, and " else "") +
                        "“${session.title}” and its chat are deleted from the server. You can undo for 3 seconds.",
                )
            },
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

@Composable
private fun TaskRow(session: SessionInfo, look: KrewdLook, onClick: () -> Unit, parentTitle: String? = null, onParent: (() -> Unit)? = null) {
    // Surface's own onClick clips the press ripple to the card's rounded shape.
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ShellDimensions.CARD_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CrewFace(look, faceOf(session, null), 30.dp, animate = false)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (session.routineId != null) {
                        Icon(painterResource(R.drawable.ic_schedule_24), contentDescription = "Routine chat", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                    }
                    if (session.pinned) {
                        Icon(painterResource(R.drawable.ic_pin_24), contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                    }
                    Text(session.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    session.sideLabel?.let { SideTag(it) }
                    if (session.waitingOn.isNotEmpty()) SideTag("waiting on ${session.waitingOn.size}")
                }
                if (parentTitle != null) ParentLine(parentTitle, onParent)
                if (session.lastText.isNotBlank()) {
                    Text(
                        plainPreview(session.lastText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Text(shortAge(session.updatedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** "↳ parent title" under a side session's title: one tap opens the parent. */
@Composable
private fun ParentLine(title: String, onOpen: (() -> Unit)?) {
    Text(
        "↳ $title",
        style = MaterialTheme.typography.labelMedium,
        color = if (onOpen != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(top = 2.dp)
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open the parent chat", onClick = onOpen).heightIn(min = 32.dp) else Modifier),
    )
}

/** A one-line preview of an agent's Markdown reply: link text without its address, no emphasis or code marks. */
internal fun plainPreview(text: String): String = text
    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
    .replace(Regex("""(\*\*|__|`)"""), "")
    .replace(Regex("""(?m)^\s*(#+|[-*]|\d+\.)\s+"""), "")
    .replace(Regex("""\s+"""), " ")
    .trim()

package app.krewd.ui

import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.activity.compose.BackHandler
import androidx.compose.material3.LocalContentColor
import io.github.zainbits.cohesion.GlassBackdrop
import io.github.zainbits.vitreous.GlassGroup
import io.github.zainbits.vitreous.GlassIconButton
import io.github.zainbits.vitreous.GlassShape
import io.github.zainbits.vitreous.GlassSurface
import io.github.zainbits.cohesion.glassSource
import io.github.zainbits.cohesion.rememberGlassBackdrop
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.krewd.Connection
import kotlinx.coroutines.launch
import app.krewd.KrewdViewModel
import app.krewd.R
import app.krewd.UiState
import app.krewd.constants.ShellDimensions
import app.krewd.data.AgentCommand
import app.krewd.data.ApprovalItem
import app.krewd.data.ChatItem
import app.krewd.data.ChatRef
import app.krewd.data.FilesItem
import app.krewd.data.LinkItem
import app.krewd.data.Proposal
import app.krewd.data.ProposalItem
import app.krewd.data.ReplyNote
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import app.krewd.data.NoticeItem
import app.krewd.data.PendingFile
import app.krewd.data.ImageRef
import app.krewd.data.PlanItem
import app.krewd.data.SwitchItem
import app.krewd.data.TextItem
import app.krewd.data.ToolItem
import app.krewd.data.UserItem

@Composable
fun ChatScreen(state: UiState, viewModel: KrewdViewModel) {
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
    // Only your own scrolling (drag, fling, a jump) decides it: rows and text arriving never do, so a reply that
    // streams in below you does not pull you back down. Scroll to the bottom again and it follows again.
    var follow by remember(listState) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to !listState.canScrollBackward }
            .collect { (scrolling, atBottom) -> if (scrolling) follow = atBottom }
    }
    // Sending a message takes you down to it.
    val lastKey = items.lastOrNull()?.key
    LaunchedEffect(lastKey) { if (items.lastOrNull() is UserItem) follow = true }
    LaunchedEffect(rowCount, lastLength) {
        if (rowCount == 0 || state.chatFocus != null) return@LaunchedEffect
        if (follow) listState.scrollToItem(0)
    }
    // The reply streaming in now. While you are scrolled up, replies hold back new text (nothing grows, so nothing
    // you read moves) and go on from there once you are back at the bottom.
    val liveKey = (items.lastOrNull() as? TextItem)?.takeIf { busy && !it.isThought }?.key
    val liveReply = remember(listState, liveKey) { LiveReply(liveKey) { !follow } }

    val bars = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues()
    var settingsOpen by rememberSaveable(state.openSessionId) { mutableStateOf(false) }
    // The title opened into its quick panel (models, effort); back, or a tap on the chat, folds it.
    var titleOpen by rememberSaveable(state.openSessionId) { mutableStateOf(false) }
    // The context ring's card above the composer.
    var contextOpen by remember(state.openSessionId) { mutableStateOf(false) }
    BackHandler(enabled = titleOpen) { titleOpen = false }
    // This chat's sign-in requests, at the bottom of the chat (also under Needs you on the Krewd tab).
    val signIns = state.secrets.filter { it.pending && it.sessionId == session?.id }
    var filling by remember(state.openSessionId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Height of everything pinned at the bottom (composer, chips, keyboard), measured, so the last message
    // always scrolls clear of the composer however tall it grows.
    var bottomStack by remember { mutableStateOf(ShellDimensions.COMPOSER_MIN_HEIGHT * 2) }
    // The strip of linked chats under the title (parent, side sessions, chats this one waits on), measured too.
    var stripHeight by remember(state.openSessionId) { mutableStateOf(0.dp) }
    var reviewing by remember(state.openSessionId) { mutableStateOf<String?>(null) }
    var sideOpen by rememberSaveable(state.openSessionId) { mutableStateOf(false) }
    val known: (String) -> Boolean = { id -> state.session(id) != null || state.archived.orEmpty().any { it.id == id } }
    val hasStrip = session != null && (session.parentId != null || session.waitingOn.isNotEmpty() || state.sessions.any { it.parentId == session.id })

    // Margin notes (MarginNotes.kt): replies to parts of the agent's messages, waiting for the next message.
    val notes = session?.let { state.notesOf(it.id) }.orEmpty()
    var flashPart by remember(state.openSessionId) { mutableStateOf<String?>(null) }
    var focusNote by remember(state.openSessionId) { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val addNote: (String, String) -> Unit = { ref, quote ->
        session?.let { s -> viewModel.addNote(s.id, quote, ref)?.let { focusNote = it - 1 } }
    }
    val jump: (ReplyNote) -> Unit = jump@{ note ->
        val key = note.itemKey ?: return@jump
        val row = rows.indexOfFirst { it.key == key }
        if (row < 0) {
            Toast.makeText(context, "That part is no longer in this chat", Toast.LENGTH_SHORT).show()
            return@jump
        }
        scope.launch {
            listState.animateScrollToItem(signIns.size + row)
            flashPart = note.ref
            delay(1_600)
            if (flashPart == note.ref) flashPart = null
        }
    }
    val noteHooks = NoteHooks(
        swipe = state.swipeToNote,
        marks = notes.withIndex().filter { it.value.ref != null }.groupBy({ it.value.ref!! }, { it.index + 1 }),
        flash = flashPart,
        onNote = addNote,
        onSelectionNote = { itemKey, text, quote ->
            // The part the words are in, so the note shows there; words across parts note the whole reply.
            val words = quote.replace(Regex("\\s+"), " ").trim()
            val block = markdownBlocks(text).firstOrNull { (_, plain) -> plain.replace(Regex("\\s+"), " ").contains(words) }?.first
            addNote(if (block != null) "$itemKey#$block" else itemKey, quote)
        },
    )
    // Liquid glass (Vitreous): the chat list is what the title bar and composer show through.
    val backdrop = rememberGlassBackdrop()
    val glassOn = LocalKrewdGlass.current.on
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Rows that open and close keep their top edge in place (KeepInPlace.kt).
        val holdRow = remember(listState) { { key: Any -> listState.holdRow(key) } }
        // Everything the glass shows through: the chat.
        Box(Modifier.fillMaxSize().then(if (glassOn) Modifier.glassSource(backdrop) else Modifier).background(MaterialTheme.colorScheme.surface)) {
        CompositionLocalProvider(LocalHoldRow provides holdRow, LocalNoteHooks provides noteHooks, LocalLiveReply provides liveReply) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ShellDimensions.CONTENT_HORIZONTAL_PADDING,
                end = ShellDimensions.CONTENT_HORIZONTAL_PADDING,
                top = bars.calculateTopPadding() + ShellDimensions.TITLE_PILL_TOP_GAP +
                    ShellDimensions.TITLE_PILL_HEIGHT + ShellDimensions.CONTENT_CLEARANCE + (if (hasStrip) stripHeight else 0.dp),
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
            items(rows, key = { it.key }) { item ->
                ChatRow(item, state, viewModel, session?.isBusy == true, commands, known, onReview = { reviewing = it }, onJump = jump)
            }
            if (state.timelineLoading && rows.isEmpty()) {
                item(key = "loading") { CircularProgressIndicator(Modifier.padding(24.dp)) }
            }
        }
        }
        }
        // The picture-in-picture stays between the title bar and the composer, so it is drawn outside what the
        // glass records: its live video would otherwise redraw the glass every frame.
        val pipTab = state.pipTab(session)
        if (session != null && pipTab != null) {
            val tab = state.browser?.tabs?.firstOrNull { it.id == pipTab } ?: session.browserTab?.takeIf { it.id == pipTab }
            BrowserPip(
                tabId = pipTab,
                label = listOfNotNull(state.engine(session.engine)?.name, tab?.host?.ifBlank { null }).joinToString(" · "),
                viewModel = viewModel,
                top = bars.calculateTopPadding() + ShellDimensions.TITLE_PILL_TOP_GAP + ShellDimensions.TITLE_PILL_HEIGHT +
                    (if (hasStrip) stripHeight else 0.dp),
                bottom = bottomStack,
                onClose = { viewModel.hidePip(session.id, pipTab) },
                onOpen = { viewModel.openBrowserAt(pipTab) },
            )
        }

        // Scrolled up from the bottom: one tap goes back down and follows the reply again.
        androidx.compose.animation.AnimatedVisibility(
            visible = !follow && rowCount > 0,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomStack + 12.dp),
        ) {
            // A round glass button over the chat (plain while the glass is off).
            CompositionLocalProvider(LocalKrewdBackdrop provides if (glassOn) backdrop else null) {
                KrewdIconPill(
                    onClick = { follow = true; scope.launch { listState.scrollToItem(0) } },
                    icon = R.drawable.ic_arrow_down_24,
                    contentDescription = "Go to latest",
                    size = 44.dp,
                )
            }
        }

        signIns.firstOrNull { it.id == filling }?.let { ask -> SecretSheet(ask = ask, viewModel = viewModel, onDismiss = { filling = null }) }
        if (titleOpen) {
            // A tap anywhere on the chat folds the title's panel (and does nothing else).
            Box(
                Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    titleOpen = false
                },
            )
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            ChatTopBar(
                state, viewModel, backdrop,
                expanded = titleOpen,
                onToggle = { titleOpen = !titleOpen },
                onSettings = { titleOpen = false; settingsOpen = true },
            )
            if (session != null) {
                LinkedChatsStrip(
                    state, session, onOpen = viewModel::openSession,
                    modifier = Modifier
                        .onSizeChanged { stripHeight = with(density) { it.height.toDp() } }
                        .padding(horizontal = ShellDimensions.TITLE_PILL_START_MARGIN)
                        .padding(top = 6.dp),
                )
            }
        }
        if (settingsOpen && session != null) {
            ChatSettingsSheet(state, viewModel, session, onDismiss = { settingsOpen = false }, onSideSession = { settingsOpen = false; sideOpen = true })
        }
        if (sideOpen && session != null) {
            NewTaskSheet(state, viewModel, session.engine, onDismiss = { sideOpen = false }, onOpenSettings = {}, parent = session)
        }
        reviewing?.let { id ->
            val proposal = state.proposals.firstOrNull { it.id == id }
                ?: (state.timeline.firstOrNull { it is ProposalItem && it.proposal.id == id } as? ProposalItem)?.proposal
            if (proposal != null) {
                NewTaskSheet(state, viewModel, proposal.engine ?: session?.engine, onDismiss = { reviewing = null }, onOpenSettings = {}, proposal = proposal)
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { bottomStack = with(density) { it.height.toDp() } }
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(ShellDimensions.COMPOSER_MARGIN),
        ) {
            if (session != null) {
                val files = state.draft(session.id)
                val tabs = state.draftTabs[session.id].orEmpty()
                val chats = state.chatsOf(session.id)
                DraftChatChips(chats, onRemove = { viewModel.removeDraftChat(session.id, it) }, Modifier.padding(start = 4.dp, bottom = 4.dp))
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
                NoteTray(
                    notes = notes,
                    focus = focusNote,
                    onFocused = { focusNote = null },
                    onJump = jump,
                    onText = { i, text -> viewModel.setNoteText(session.id, i, text) },
                    onRemove = { viewModel.removeNote(session.id, it) },
                    onClear = { viewModel.setDraftNotes(session.id, emptyList()) },
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                val pick = rememberFilePicker { viewModel.attach(session.id, it) }
                val live = state.live[session.id]
                Composer(
                    backdrop = backdrop,
                    sessionId = session.id,
                    initialText = remember(session.id) { viewModel.messageDraft(session.id) },
                    onDraft = { viewModel.setMessageDraft(session.id, it) },
                    busy = busy,
                    steps = if (busy) live?.steps else null,
                    onSteps = { scope.launch { listState.animateScrollToItem(0) } },
                    contextUsed = session.contextUsed,
                    contextSize = session.contextSize,
                    onContext = { contextOpen = !contextOpen },
                    steering = state.engine(session.engine)?.steering == true,
                    commands = commands,
                    files = files,
                    hasTabs = tabs.isNotEmpty() || chats.isNotEmpty() || notes.isNotEmpty(),
                    mentions = { typed ->
                        if (typed.isNotEmpty()) viewModel.loadArchived()
                        mentionCandidates(state, session.id, typed, chats.mapTo(HashSet()) { it.id })
                    },
                    engines = state.engines,
                    onMention = { viewModel.addDraftChat(session.id, it) },
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    onAttach = pick,
                    onRemoveFile = { viewModel.removeAttachment(session.id, it) },
                )
            }
        }
        val used = session?.contextUsed
        val window = session?.contextSize
        if (contextOpen && session != null && used != null && window != null && window > 0) {
            BackHandler { contextOpen = false }
            Box(
                Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    contextOpen = false
                },
            )
            ContextCard(
                backdrop = backdrop,
                used = used,
                size = window,
                onCompact = if (state.connection is Connection.Online) ({ viewModel.compact(session.id) }) else null,
                compactNative = session.compact == "native",
                onClose = { contextOpen = false },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = ShellDimensions.COMPOSER_MARGIN + 8.dp, bottom = bottomStack + 8.dp),
            )
        }
    }
}

/**
 * Back, and the title with the agent and its settings under it. Tapping the title opens it into the quick
 * panel (models, effort, all settings; [ChatQuickPanel]) and tapping it again folds it. On liquid glass
 * (Vitreous) both pieces draw in one pass, can be pulled like liquid, merge when pulled together, and
 * their text turns dark over bright content.
 */
@Composable
private fun ChatTopBar(
    state: UiState,
    viewModel: KrewdViewModel,
    backdrop: GlassBackdrop,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
) {
    val session = state.openSession
    val barModifier = Modifier
        .fillMaxWidth()
        .windowInsetsPadding(
            WindowInsets.statusBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        )
        .padding(horizontal = ShellDimensions.TITLE_PILL_START_MARGIN)
        .padding(top = ShellDimensions.TITLE_PILL_TOP_GAP)
    val titleBody: @Composable () -> Unit = {
        Column(Modifier.animateContentSize(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow))) {
            ChatTitle(state, expanded)
            if (expanded && session != null) ChatQuickPanel(state, viewModel, session, onAllSettings = onSettings)
        }
    }
    if (!LocalKrewdGlass.current.on) {
        // Glass off (Settings, battery saver or heat): the plain pills, no stretch.
        Row(barModifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            FloatingPill {
                IconButton(onClick = viewModel::closeSession) {
                    Icon(painterResource(R.drawable.ic_chevron_left_24), contentDescription = "Back")
                }
            }
            Surface(
                onClick = onToggle,
                enabled = session != null,
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = androidx.compose.foundation.BorderStroke(ShellDimensions.PILL_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 2.dp,
                modifier = Modifier.weight(1f),
            ) { titleBody() }
        }
        return
    }
    GlassGroup(
        backdrop = backdrop,
        modifier = barModifier,
        merge = 10.dp,
    ) { glassRow ->
    Row(
        modifier = glassRow,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        GlassIconButton(
            onClick = viewModel::closeSession,
            backdrop = backdrop,
            contentDescription = "Back",
            size = ShellDimensions.TITLE_PILL_HEIGHT,
        ) {
            Icon(painterResource(R.drawable.ic_chevron_left_24), contentDescription = null)
        }
        GlassSurface(
            backdrop = backdrop,
            modifier = Modifier.weight(1f),
            shape = GlassShape.rounded(26.dp),
            elastic = true,
            onClick = onToggle,
            enabled = session != null,
        ) { titleBody() }
    }
    }
}

/** The chat's title, with the agent and its settings under it, and the caret that turns down when open. */
@Composable
private fun ChatTitle(state: UiState, expanded: Boolean) {
    val session = state.openSession
    val ink = LocalContentColor.current
    val turn by animateFloatAsState(if (expanded) 90f else 0f, animationSpec = tween(240), label = "caret")
    Row(
        Modifier.heightIn(min = ShellDimensions.TITLE_PILL_HEIGHT).padding(start = 16.dp, end = 10.dp, top = if (expanded) 6.dp else 0.dp, bottom = if (expanded) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                session?.title ?: "Task",
                style = MaterialTheme.typography.titleMedium,
                maxLines = if (expanded) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(lookFor(session.engine, state.engines).color, CircleShape))
                    Text(
                        chatSubtitle(state, session),
                        style = MaterialTheme.typography.labelMedium,
                        color = ink.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (session != null) {
            Icon(
                painterResource(R.drawable.ic_chevron_right_24),
                contentDescription = if (expanded) "Close chat options" else "Chat options",
                tint = ink.copy(alpha = 0.75f),
                modifier = Modifier.padding(start = 6.dp).size(24.dp).rotate(turn),
            )
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
    backdrop: GlassBackdrop,
    sessionId: String,
    initialText: String,
    onDraft: (String) -> Unit,
    busy: Boolean,
    steps: Int?,
    onSteps: () -> Unit,
    contextUsed: Long?,
    contextSize: Long?,
    onContext: () -> Unit,
    steering: Boolean,
    commands: List<AgentCommand>,
    files: List<PendingFile>,
    hasTabs: Boolean,
    mentions: (String) -> List<MentionCandidate>,
    engines: List<app.krewd.data.EngineInfo>,
    onMention: (ChatRef) -> Unit,
    onSend: (String, String?) -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRemoveFile: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Unsent text is kept per chat on the phone, so leaving the chat or restarting the app loses nothing.
    var value by rememberSaveable(sessionId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))
    }
    fun edit(next: TextFieldValue) {
        if (next.text != value.text) onDraft(next.text)
        value = next
    }
    val text = value.text
    val mention = if (value.selection.collapsed) mentionQuery(text, value.selection.start) else null
    val query = if (value.selection.collapsed && mention == null) commandQuery(text, value.selection.start) else null
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
    ComposerShell(backdrop, modifier.fillMaxWidth()) {
        val textColor = colors.onSurface
        val hintColor = colors.onSurfaceVariant
        CompositionLocalProvider(LocalContentColor provides textColor) {
        Column(Modifier.animateContentSize().padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp)) {
            // Attached files fill the composer's top, so it grows instead of stacking chips above it.
            AttachmentTray(files, onRemove = onRemoveFile, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
            if (mention != null) {
                ChatSuggestions(mentions(mention.typed), engines, onPick = {
                    edit(removeMention(value, mention))
                    onMention(it.ref())
                }, Modifier.padding(top = 2.dp))
            } else if (query != null) {
                CommandSuggestions(matches, onPick = { edit(applyCommand(value, query, it)) }, Modifier.padding(top = 2.dp))
            }
            BasicTextField(
                value = value,
                onValueChange = ::edit,
                visualTransformation = highlight,
                onTextLayout = { layout = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                cursorBrush = SolidColor(colors.primary),
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                decorationBox = { field ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                if (busy) "Steer the agent…" else "Reply to Krewd · @ adds a chat",
                                style = MaterialTheme.typography.bodyLarge,
                                color = hintColor,
                            )
                        }
                        Box(Modifier.commandPills(layout, ranges, ink.copy(alpha = 0.16f))) { field() }
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StretchIconButton(onClick = onAttach, icon = R.drawable.ic_add_24, contentDescription = "Attach files")
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
                    ContextRing(contextUsed, contextSize, onContext)
                    Spacer(Modifier.width(6.dp))
                }
                val stop = busy && !hasDraft
                val enabled = stop || ((text.isNotBlank() || ready) && !uploading)
                // A solid round button that stretches like the glass pieces (no glass of its own).
                KrewdIconPill(
                    onClick = {
                        if (stop) {
                            onStop()
                        } else {
                            onSend(text.trim(), if (busy) delivery.id else null)
                            value = TextFieldValue("") // send() also forgets the saved draft
                        }
                    },
                    icon = if (stop) R.drawable.ic_stop_24 else R.drawable.ic_send_24,
                    contentDescription = if (stop) "Stop" else if (uploading) "Uploading" else "Send",
                    enabled = enabled,
                    glass = false,
                    size = 40.dp,
                    iconSize = 20.dp,
                    color = if (enabled) colors.primary else colors.surfaceContainerHighest,
                    contentColor = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
                    // 10 dp from the right edge, like the bottom (6 dp padding + the row's 4 dp).
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
        }
    }
}

/**
 * The composer's panel: liquid glass (Vitreous; the chat shows through, blurred and bent at the edge), or
 * the plain panel while glass is off. 30 dp corners, so the 40 dp send button 10 dp in from the corner
 * sits on a concentric curve.
 */
@Composable
private fun ComposerShell(backdrop: GlassBackdrop, modifier: Modifier, content: @Composable () -> Unit) {
    if (LocalKrewdGlass.current.on) {
        // The composer keeps its own colours (no light/dark switching while you type).
        GlassSurface(backdrop = backdrop, modifier = modifier, shape = GlassShape.rounded(30.dp), clipContent = false) { content() }
    } else {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(ShellDimensions.PILL_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 2.dp,
            content = content,
        )
    }
}

/** How full the agent's context window is: a small ring with the percentage; tap for the card with the tokens. */
@Composable
private fun ContextRing(used: Long, size: Long, onClick: () -> Unit) {
    val fraction = (used.toFloat() / size).coerceIn(0f, 1f)
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { fraction },
            modifier = Modifier.size(28.dp),
            color = contextTone(fraction),
            trackColor = colors.surfaceContainerHighest,
            strokeWidth = 2.5.dp,
            gapSize = 0.dp,
            strokeCap = StrokeCap.Round,
        )
        Text(
            "${(fraction * 100).toInt()}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 9.sp),
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
    }
}

@Composable
private fun contextTone(fraction: Float) = when {
    fraction >= 0.9f -> MaterialTheme.colorScheme.error
    fraction >= 0.7f -> app.krewd.LocalKrewdColors.current.needs
    else -> MaterialTheme.colorScheme.primary
}

/**
 * The context ring's card: a small glass panel above the composer with the tokens, and a solid Compact
 * button. Drawn in the chat (not a popup window), so the glass shows the chat behind it.
 */
@Composable
private fun ContextCard(
    backdrop: GlassBackdrop,
    used: Long,
    size: Long,
    onCompact: (() -> Unit)?,
    compactNative: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = (used.toFloat() / size).coerceIn(0f, 1f)
    val tone = contextTone(fraction)
    val body: @Composable () -> Unit = {
        val ink = LocalContentColor.current
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CONTEXT", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = tone)
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = tone,
                trackColor = ink.copy(alpha = 0.15f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            TokenLine("Used", used)
            TokenLine("Left", (size - used).coerceAtLeast(0))
            TokenLine("Window", size)
            if (onCompact != null) {
                FilledTonalButton(
                    onClick = { onClose(); onCompact() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(36.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_compress_24), contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Compact now", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                }
                Text(
                    if (compactNative) "Runs the agent's /compact after this turn." else "A fresh session takes over after this turn.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ink.copy(alpha = 0.66f),
                )
            }
        }
    }
    if (LocalKrewdGlass.current.on) {
        GlassSurface(backdrop = backdrop, modifier = modifier.width(212.dp), shape = GlassShape.rounded(22.dp)) { body() }
    } else {
        Surface(
            modifier = modifier.width(212.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 3.dp,
        ) { body() }
    }
}

@Composable
private fun TokenLine(label: String, tokens: Long) {
    val ink = LocalContentColor.current
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
        Text("%,d".format(tokens), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun ChatRow(
    item: ChatItem,
    state: UiState,
    viewModel: KrewdViewModel,
    busy: Boolean,
    commands: List<AgentCommand>,
    known: (String) -> Boolean,
    onReview: (String) -> Unit,
    onJump: (ReplyNote) -> Unit,
) {
    when (item) {
        is UserItem -> when (item.purpose) {
            "routine" -> RoutineRunRow(item)
            "compact" -> CompactRow(item)
            "switch" -> SwitchPendingRow(item, state.engine(item.switchTo)?.name ?: item.switchTo ?: "another agent")
            "result", "prompt" -> FromChatRow(item, exists = item.fromId?.let(known) == true, onOpen = { item.fromId?.let(viewModel::openSession) })
            else -> UserRow(item, busy, commands, known, onOpenChat = viewModel::openSession, onResend = { item.clientId?.let(viewModel::resend) }, onJump = onJump)
        }
        is LinkItem -> LinkRow(item, exists = known(item.chatId), onOpen = { viewModel.openSession(item.chatId) })
        is ProposalItem -> {
            // The live list knows the newest state; the timeline row keeps the one it was given.
            val proposal: Proposal = state.proposals.firstOrNull { it.id == item.proposal.id } ?: item.proposal
            ProposalCard(
                proposal = proposal,
                chatTitle = null,
                look = null,
                onReview = { onReview(proposal.id) },
                onDismiss = { viewModel.dismissProposal(proposal) },
                onOpenResult = { id -> if (known(id)) viewModel.openSession(id) },
            )
        }
        is TextItem -> if (item.isThought) ThoughtRow(item.key, item.text) else ReplyText(item)
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
private fun UserRow(
    item: UserItem,
    busy: Boolean,
    commands: List<AgentCommand>,
    known: (String) -> Boolean,
    onOpenChat: (String) -> Unit,
    onResend: () -> Unit,
    onJump: (ReplyNote) -> Unit,
) {
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
                if (item.notes.isNotEmpty()) SentNotes(item.notes, onBubble, onJump)
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
                if (item.chats.isNotEmpty()) SentChatChips(item.chats, known, onBubble, onOpenChat)
                if (item.attachments.isNotEmpty()) SentFiles(item.attachments, onBubble)
                DeliveryLine(item, busy, onBubble)
            }
        }
    }
}

/** An agent's reply: its parts take margin notes (tap, swipe, or Note in the selection menu). */
@Composable
private fun ReplyText(item: TextItem) {
    val hooks = LocalNoteHooks.current
    val live = LocalLiveReply.current
    val streaming = live.key == item.key
    val smooth = rememberSmoothText(item.key, item.text, live = streaming, paused = live.paused)
    val body: @Composable () -> Unit = {
        if (streaming || smooth.text.length < item.text.length) LiveMarkdown(smooth.text, smooth.fade) else Markdown(smooth.text)
    }
    if (hooks == null || item.role != "assistant") {
        SelectionContainer { body() }
        return
    }
    CompositionLocalProvider(LocalNoteItem provides item.key) {
        Box(Modifier.noteSelectionMenu { quote -> hooks.onSelectionNote(item.key, item.text, quote) }) {
            SelectionContainer { body() }
        }
    }
}

/** A routine's run, as Krewd sent it: a header with the full prompt behind a tap. */
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
            if (item.state != "cancelled" && item.continued) {
                // A follow-up attempt goes on in the same chat and agent session; a first attempt has its own chat.
                Text(
                    "Same agent session as the attempt above",
                    style = MaterialTheme.typography.labelSmall,
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
private fun ThoughtRow(key: String, text: String) {
    var open by remember { mutableStateOf(false) }
    val hold = LocalHoldRow.current
    Column(
        Modifier.fillMaxWidth().clickable { hold(key); open = !open }.animateContentSize().padding(horizontal = 4.dp),
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
    val viewer = LocalImageViewer.current
    val refs = item.images.map { ImageRef.Tool(it) }
    ToolCard(item) {
        item.images.forEachIndexed { i, name -> ToolImage(name, loadImage, onOpen = { viewer?.open?.invoke(refs, i) }) }
    }
}

/** A picture from a tool result, full card width; tap it to see it full screen. */
@Composable
private fun ToolImage(name: String, load: suspend (String) -> ImageBitmap?, onOpen: () -> Unit) {
    var failed by remember(name) { mutableStateOf<String?>(null) }
    val bitmap by produceState<ImageBitmap?>(null, name) {
        value = try {
            load(name) ?: run { failed = "Not connected to Krewd"; null }
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
            modifier = Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1)).clip(shape).clickable(onClick = onOpen),
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




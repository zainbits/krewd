package app.crew.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.crew.Connection
import app.crew.CrewViewModel
import app.crew.R
import app.crew.UiState
import app.crew.constants.LiquidNavDimensions
import app.crew.constants.ShellDimensions
import app.crew.data.BrowserFrame
import app.crew.data.BrowserTab
import app.crew.data.SessionInfo
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

private const val TAB_REFRESH_MS = 3_000L
private const val DOUBLE_TAP_MS = 300L
private const val LONG_PRESS_MS = 500L
private const val MAX_ZOOM = 3f
private val SMALL_PILL = 36.dp
private val FAVICON_COLORS = listOf(
    Color(0xFFFF8A3D), Color(0xFF5B9BFF), Color(0xFF3DBE7A), Color(0xFFF2C744),
    Color(0xFFB57BFF), Color(0xFFFF6B6B), Color(0xFF34C3D6),
)

private fun favColor(tab: BrowserTab) = FAVICON_COLORS[abs(tab.host.hashCode()) % FAVICON_COLORS.size]

/**
 * The agents' browser: its real tabs as pills, back/forward/reload and the URL, and the selected tab
 * live below. Cursor mode works like a trackpad; touch mode like a touch screen. Horizontal shows the
 * normal 1080p window (pinch to zoom on the phone); vertical lays the page out for the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(state: UiState, viewModel: CrewViewModel, landscape: Boolean) {
    val online = state.connection is Connection.Online
    LaunchedEffect(online) {
        while (online) {
            viewModel.refreshTabs()
            delay(TAB_REFRESH_MS)
        }
    }
    // Cursor or touch is remembered per agent: the one whose chat you came from.
    val engineId = state.sessions.firstOrNull { it.id == state.lastSessionId }?.engine
    var cursor by remember(engineId) { mutableStateOf(viewModel.inputCursor(engineId)) }
    var keyboard by remember { mutableStateOf(false) }
    var allTabs by remember { mutableStateOf(false) }
    var editingUrl by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Pair<BrowserTab, Boolean>?>(null) } // tab, true = picture-in-picture
    val browser = state.browser
    val tab = state.browserTab
    val clipboard = LocalClipboardManager.current

    fun send(vararg pairs: Pair<String, Any>) {
        val id = tab?.id ?: return
        viewModel.tabInput(id, JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } })
    }

    val tabActions = TabActions(
        onSelect = { viewModel.selectTab(it.id) },
        onAddToChat = { picker = it to false },
        onPip = { picker = it to true },
        onCopy = {
            clipboard.setText(AnnotatedString(it.url))
            viewModel.toast("Link copied")
        },
        onClose = { viewModel.closeTab(it.id) },
    )

    val navClearance = if (landscape) 0.dp else
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + LiquidNavDimensions.BOTTOM_GAP + LiquidNavDimensions.BAR_HEIGHT + 8.dp

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        if (!landscape) {
            TabsRow(state, tabActions, onNew = { viewModel.newTab { editingUrl = true } }, onAll = { allTabs = true })
        }
        ControlsRow(
            tab = tab,
            tabCount = browser?.tabs?.size ?: 0,
            landscape = landscape,
            editing = editingUrl,
            onEditing = { editingUrl = it },
            vertical = tab?.vertical == true,
            viewOnly = tab?.agentActive == true,
            highQuality = state.highQuality,
            cursor = cursor,
            onNav = { action -> send("type" to "navigate", "action" to action) },
            onGo = { url -> send("type" to "navigate", "url" to url) },
            onCopy = {
                tab?.let { clipboard.setText(AnnotatedString(it.url)) }
                viewModel.toast("URL copied")
            },
            onVertical = { on -> tab?.let { viewModel.setTabVertical(it.id, on) } },
            onHighQuality = viewModel::setHighQuality,
            onCursor = {
                cursor = it
                viewModel.setInputCursor(engineId, it)
            },
            onKeyboard = { keyboard = true },
            onAll = { allTabs = true },
        )
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp)) {
            when {
                browser == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                !browser.available -> Message(
                    "The browser is not running",
                    "It starts when the server's desktop is logged in. On the server: ./master.sh start browser",
                )
                tab == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No open tabs", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { viewModel.newTab { editingUrl = true } }) { Text("Open a new tab") }
                }
                else -> LiveView(
                    tab = tab,
                    viewModel = viewModel,
                    vertical = tab.vertical,
                    cursor = cursor,
                    agentChat = tab.sessionId?.takeIf { tab.agentActive }?.let { id -> state.sessions.firstOrNull { it.id == id } },
                    onOpenChat = viewModel::openChatOf,
                    streamError = state.streamError,
                    onEditable = { editable, _ -> if (editable) keyboard = true },
                )
            }
        }
        // An agent starting on the tab ends typing: the tab is view only while it works.
        LaunchedEffect(tab?.agentActive) { if (tab?.agentActive == true) keyboard = false }
        if (keyboard && tab != null && !tab.agentActive) {
            DirectKeyboard(
                onKey = { key, mods -> send("type" to "key", "key" to key, "modifiers" to JSONArray(mods)) },
                onText = { send("type" to "text", "text" to it) },
                onDone = { keyboard = false },
            )
        } else {
            Spacer(Modifier.height(navClearance))
        }
    }

    if (allTabs) {
        AllTabs(state, tabActions, onNew = { allTabs = false; viewModel.newTab { editingUrl = true } }, onDismiss = { allTabs = false })
    }
    picker?.let { (pickedTab, pip) ->
        ChatPicker(
            state = state,
            title = if (pip) "Show this tab on which chat?" else "Add this tab to which chat?",
            onDismiss = { picker = null },
            onPick = { sessionId ->
                picker = null
                if (pip) viewModel.showPip(sessionId, pickedTab.id) else viewModel.addTabToChat(sessionId, pickedTab)
            },
        )
    }
}

private data class TabActions(
    val onSelect: (BrowserTab) -> Unit,
    val onAddToChat: (BrowserTab) -> Unit,
    val onPip: (BrowserTab) -> Unit,
    val onCopy: (BrowserTab) -> Unit,
    val onClose: (BrowserTab) -> Unit,
)

@Composable
private fun Message(title: String, text: String) {
    SectionCard(Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- tabs

@Composable
private fun TabsRow(state: UiState, actions: TabActions, onNew: () -> Unit, onAll: () -> Unit) {
    val tabs = state.browser?.tabs.orEmpty()
    val listState = rememberLazyListState()
    val selected = tabs.indexOfFirst { it.id == state.browserTabId }
    LaunchedEffect(selected) { if (selected >= 0) listState.animateScrollToItem(selected) }
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(tabs, key = { it.id }) { tab -> TabPill(tab, tab.id == state.browserTabId, actions) }
        }
        SmallPill { IconButton(onClick = onNew, modifier = Modifier.size(SMALL_PILL)) { Icon(painterResource(R.drawable.ic_add_24), contentDescription = "New tab") } }
        TabCount(tabs.size, onAll)
    }
}

@Composable
private fun SmallPill(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainerHigh, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.height(SMALL_PILL),
        shape = CircleShape,
        color = color,
        border = androidx.compose.foundation.BorderStroke(ShellDimensions.PILL_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun TabCount(count: Int, onClick: () -> Unit) {
    SmallPill(Modifier.width(SMALL_PILL).clickable(onClick = onClick)) {
        Box(
            Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp)).padding(horizontal = 4.dp),
        ) { Text(if (count > 99) "∞" else count.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Favicon(tab: BrowserTab, size: androidx.compose.ui.unit.Dp = 22.dp) {
    Box(Modifier.size(size).background(favColor(tab), CircleShape), contentAlignment = Alignment.Center) {
        Text(
            (tab.title.ifBlank { tab.host }.firstOrNull { it.isLetterOrDigit() } ?: '·').uppercase(),
            color = Color.Black,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabPill(tab: BrowserTab, selected: Boolean, actions: TabActions) {
    var menu by remember { mutableStateOf(false) }
    Box {
        SmallPill(
            Modifier.widthIn(max = 160.dp).combinedClickable(onClick = { actions.onSelect(tab) }, onLongClick = { menu = true }),
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(Modifier.padding(start = 6.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Favicon(tab)
                Text(
                    tab.title.ifBlank { tab.host.ifBlank { "New tab" } },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tab.sessionId != null) Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
            }
        }
        TabMenu(tab, menu, onDismiss = { menu = false }, actions)
    }
}

@Composable
private fun TabMenu(tab: BrowserTab, open: Boolean, onDismiss: () -> Unit, actions: TabActions) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        Text(
            tab.title.ifBlank { tab.host },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 260.dp),
        )
        MenuRow("Add to chat", "Tell a task to use this tab") { onDismiss(); actions.onAddToChat(tab) }
        MenuRow("Show in chat", "Picture-in-picture on a task") { onDismiss(); actions.onPip(tab) }
        MenuRow("Copy link", null) { onDismiss(); actions.onCopy(tab) }
        HorizontalDivider()
        MenuRow("Close tab", null) { onDismiss(); actions.onClose(tab) }
    }
}

@Composable
private fun MenuRow(title: String, hint: String?, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title)
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        onClick = onClick,
    )
}

/** Every tab at once, for fast switching and closing. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AllTabs(state: UiState, actions: TabActions, onNew: () -> Unit, onDismiss: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val tabs = state.browser?.tabs.orEmpty()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ShellDimensions.TITLE_PILL_START_MARGIN).padding(top = ShellDimensions.TITLE_PILL_TOP_GAP, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingPill { IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_chevron_left_24), contentDescription = "Back") } }
                FloatingPill(Modifier.weight(1f)) {
                    Text(
                        if (tabs.size == 1) "1 tab" else "${tabs.size} tabs",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                FloatingPill { IconButton(onClick = onNew) { Icon(painterResource(R.drawable.ic_add_24), contentDescription = "New tab") } }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                items(tabs, key = { it.id }) { tab ->
                    var menu by remember { mutableStateOf(false) }
                    val selected = tab.id == state.browserTabId
                    val user = tab.sessionId?.let { id -> state.sessions.firstOrNull { it.id == id } }
                    Box {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onClick = { actions.onSelect(tab); onDismiss() },
                                onLongClick = { menu = true },
                            ),
                        ) {
                            Column {
                                Box(Modifier.fillMaxWidth().height(86.dp).background(favColor(tab).copy(alpha = 0.25f))) {
                                    Box(Modifier.align(Alignment.Center)) { Favicon(tab, 40.dp) }
                                    if (user != null) {
                                        Text(
                                            "In use: ${user.title}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                                                .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                                                .padding(horizontal = 8.dp, vertical = 2.dp).widthIn(max = 110.dp),
                                        )
                                    }
                                    Box(
                                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp).background(Color(0xAA000000), CircleShape)
                                            .clickable { actions.onClose(tab) },
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Close tab", tint = Color.White, modifier = Modifier.size(16.dp)) }
                                }
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(tab.title.ifBlank { tab.host.ifBlank { "New tab" } }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(tab.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        TabMenu(tab, menu, onDismiss = { menu = false }, actions.copy(onAddToChat = { onDismiss(); actions.onAddToChat(it) }, onPip = { onDismiss(); actions.onPip(it) }))
                    }
                }
            }
        }
    }
}

/** Which chat gets the tab: the one you had open last first, then the others. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatPicker(state: UiState, title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val current = state.sessions.firstOrNull { it.id == state.lastSessionId }
    val others = state.sessions.filter { it.id != current?.id }.take(8)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (current == null && others.isEmpty()) {
            Text("No chats yet. Start a task first.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        (listOfNotNull(current) + others).forEach { session ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(session.id) }.padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_chat_24), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            "Current chat".takeIf { session.id == current?.id },
                            state.engine(session.engine)?.name ?: session.engine,
                            if (session.isBusy) "working" else shortAge(session.updatedAt),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- controls

@Composable
private fun ControlsRow(
    tab: BrowserTab?,
    tabCount: Int,
    landscape: Boolean,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    vertical: Boolean,
    viewOnly: Boolean,
    highQuality: Boolean,
    cursor: Boolean,
    onNav: (String) -> Unit,
    onGo: (String) -> Unit,
    onCopy: () -> Unit,
    onVertical: (Boolean) -> Unit,
    onHighQuality: (Boolean) -> Unit,
    onCursor: (Boolean) -> Unit,
    onKeyboard: () -> Unit,
    onAll: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (landscape) TabCount(tabCount, onAll)
        SmallPill {
            Row {
                IconButton(onClick = { onNav("back") }, enabled = !viewOnly, modifier = Modifier.size(SMALL_PILL)) { Icon(painterResource(R.drawable.ic_chevron_left_24), contentDescription = "Back") }
                IconButton(onClick = { onNav("forward") }, enabled = !viewOnly, modifier = Modifier.size(SMALL_PILL)) { Icon(painterResource(R.drawable.ic_chevron_right_24), contentDescription = "Forward") }
                IconButton(onClick = { onNav("reload") }, enabled = !viewOnly, modifier = Modifier.size(SMALL_PILL)) { Icon(painterResource(R.drawable.ic_refresh_24), contentDescription = "Reload", modifier = Modifier.size(20.dp)) }
            }
        }
        SmallPill(Modifier.weight(1f)) {
            UrlField(tab, editing && !viewOnly, { if (!viewOnly) onEditing(it) }, onGo, onCopy)
        }
        Box {
            SmallPill { IconButton(onClick = { menu = true }, modifier = Modifier.size(SMALL_PILL)) { Icon(painterResource(R.drawable.ic_more_24), contentDescription = "Browser options") } }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text("Display", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                CheckRow("Horizontal (1080p)", !vertical, enabled = !viewOnly) { menu = false; onVertical(false) }
                CheckRow("Vertical (fits the phone)", vertical, enabled = !viewOnly) { menu = false; onVertical(true) }
                CheckRow("High quality (60 fps)", highQuality) { menu = false; onHighQuality(!highQuality) }
                HorizontalDivider()
                Text("Input", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                CheckRow("Cursor", cursor) { menu = false; onCursor(true) }
                CheckRow("Touch", !cursor) { menu = false; onCursor(false) }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Keyboard") }, enabled = !viewOnly, onClick = { menu = false; onKeyboard() })
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        enabled = enabled,
        text = { Text(label, fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal) },
        leadingIcon = { Text(if (checked) "✓" else " ", color = MaterialTheme.colorScheme.primary) },
        onClick = onClick,
    )
}

@Composable
private fun UrlField(tab: BrowserTab?, editing: Boolean, onEditing: (Boolean) -> Unit, onGo: (String) -> Unit, onCopy: () -> Unit) {
    val focus = remember { FocusRequester() }
    var text by remember(tab?.id, editing) { mutableStateOf(tab?.url?.takeUnless { it == "about:blank" }.orEmpty()) }
    if (editing) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = {
                if (text.isNotBlank()) onGo(toUrl(text))
                onEditing(false)
            }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).focusRequester(focus),
        )
    } else {
        Row(Modifier.fillMaxSize().clickable { onEditing(true) }.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tab?.url?.removePrefix("https://")?.removePrefix("http://")?.removePrefix("www.")?.ifBlank { null } ?: "Search or type a URL",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (tab != null) {
                IconButton(onClick = onCopy, modifier = Modifier.size(SMALL_PILL)) {
                    Icon(painterResource(R.drawable.ic_copy_24), contentDescription = "Copy the URL", modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

/** A typed address, or a web search for anything that does not look like one. */
private fun toUrl(input: String): String {
    val text = input.trim()
    return when {
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text) || text.startsWith("about:") -> text
        !text.contains(' ') && (text.contains('.') || text.startsWith("localhost")) -> "https://$text"
        else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(text, "UTF-8")
    }
}

// ---------------------------------------------------------------- live view and input

/**
 * The tab, live. Cursor mode: drag moves the pointer (hover works), tap clicks, tap then touch again and
 * drag holds the button (select, drag), two quick taps double-click, a two-finger tap right-clicks, and
 * the bar on the right scrolls. Touch mode: tap clicks where you touch, long-press right-clicks, a drag
 * holds the button, two fingers scroll. Horizontal: pinch zooms the picture on the phone only.
 */
@Composable
private fun LiveView(
    tab: BrowserTab,
    viewModel: CrewViewModel,
    vertical: Boolean,
    cursor: Boolean,
    agentChat: SessionInfo?,
    onOpenChat: (String) -> Unit,
    streamError: String?,
    onEditable: (Boolean, Boolean) -> Unit,
) {
    // View only while an agent works in the tab: nothing reaches the page (no clicks, hover, scrolls or
    // keys), but the cursor still moves on the phone, so pinch zoom and pushing it to an edge pan the view.
    val viewOnly = agentChat != null
    val cursor = cursor || viewOnly
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    DisposableEffect(tab.id) {
        viewModel.watchTab(tab.id)
        onDispose { viewModel.unwatch(tab.id) }
    }
    val current = frame?.takeIf { it.tabId == tab.id }
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val boxW = maxWidth
        val boxH = maxHeight
        // Vertical: the page is laid out for exactly this box. Horizontal: the normal window.
        // The tab's own layout comes from the server (an agent using it resets it to horizontal); this
        // only keeps a vertical tab sized to this box, for example after the phone rotates.
        LaunchedEffect(tab.id, vertical, boxW, boxH) {
            val box = Triple(boxW.value.roundToInt(), boxH.value.roundToInt(), min(density.density, 1920f / boxH.value))
            viewModel.viewBox = box
            if (vertical) viewModel.applyView(tab.id, true, box.first, box.second, box.third)
        }

        if (current == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(streamError ?: "Connecting to the tab…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@BoxWithConstraints
        }
        val ratio = current.bitmap.width.toFloat() / current.bitmap.height
        val fitW = if (ratio > boxW / boxH) boxW else boxH * ratio
        val fitH = if (ratio > boxW / boxH) boxW / ratio else boxH
        val wPx = with(density) { fitW.toPx() }
        val hPx = with(density) { fitH.toPx() }

        var zoom by remember(tab.id) { mutableFloatStateOf(1f) }
        var panX by remember(tab.id) { mutableFloatStateOf(0f) }
        var panY by remember(tab.id) { mutableFloatStateOf(0f) }
        if (vertical && zoom != 1f) { zoom = 1f; panX = 0f; panY = 0f }
        var cx by remember(tab.id) { mutableFloatStateOf(wPx / 3) }
        var cy by remember(tab.id) { mutableFloatStateOf(hPx / 4) }
        var pressed by remember { mutableStateOf(false) }
        var lastTapAt by remember { mutableLongStateOf(0L) }
        var lastTapPos by remember { mutableStateOf(Offset.Zero) }

        fun clampPan() {
            panX = panX.coerceIn(0f, (zoom - 1f) * wPx)
            panY = panY.coerceIn(0f, (zoom - 1f) * hPx)
        }
        // Box position -> 0..1 of the page's viewport, through the phone-side zoom.
        fun fx(x: Float) = ((x + panX) / zoom / wPx).coerceIn(0f, 1f)
        fun fy(y: Float) = ((y + panY) / zoom / hPx).coerceIn(0f, 1f)
        fun mouse(action: String, x: Float, y: Float, button: String = "left", clicks: Int = 1, held: Boolean = false, result: ((JSONObject) -> Unit)? = null) {
            if (viewOnly) return
            val e = JSONObject().put("type", "mouse").put("action", action).put("x", fx(x).toDouble()).put("y", fy(y).toDouble())
                .put("button", button).put("clicks", clicks)
            if (held) e.put("held", true)
            viewModel.tabInput(tab.id, e, result)
        }
        fun clickResult(r: JSONObject) = onEditable(r.optBoolean("editable"), r.optBoolean("password"))
        fun wheel(x: Float, y: Float, dyFraction: Float) {
            if (viewOnly) return
            viewModel.tabInput(tab.id, JSONObject().put("type", "scroll").put("x", fx(x).toDouble()).put("y", fy(y).toDouble()).put("dx", 0).put("dy", dyFraction.toDouble()))
        }

        val slop = LocalViewConfiguration.current.touchSlop
        val doubleTapDistance = with(density) { 40.dp.toPx() }

        // The whole area takes gestures (in cursor mode the empty space below a wide page is trackpad too);
        // positions are mapped into the picture, which sits at the top, centred.
        val originX = (with(density) { boxW.toPx() } - wPx) / 2
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(tab.id, cursor, vertical, viewOnly, wPx, hPx) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val down = object {
                            val uptimeMillis = first.uptimeMillis
                            val position = first.position - Offset(originX, 0f)
                        }
                        if (!cursor && (down.position.x !in 0f..wPx || down.position.y !in 0f..hPx)) return@awaitEachGesture
                        val start = down.uptimeMillis
                        val again = start - lastTapAt < DOUBLE_TAP_MS && (down.position - lastTapPos).getDistance() < doubleTapDistance
                        var moved = 0f
                        var fingers = 1
                        var multi: String? = null // pinch or scroll, once two fingers decide
                        var zoomSpan = 1f
                        var scrollAcc = 0f
                        var longFired = false
                        var held = false
                        var end = start
                        if (!cursor) {
                            cx = down.position.x; cy = down.position.y
                        }
                        while (true) {
                            val event = if (!cursor && !longFired && fingers == 1 && moved < slop) {
                                withTimeoutOrNull(LONG_PRESS_MS) { awaitPointerEvent(PointerEventPass.Main) }
                            } else {
                                awaitPointerEvent(PointerEventPass.Main)
                            }
                            if (event == null) {
                                // Touch mode: held still for half a second is a right-click.
                                longFired = true
                                mouse("click", cx, cy, button = "right")
                                continue
                            }
                            end = event.changes.maxOf { it.uptimeMillis }
                            val touching = event.changes.filter { it.pressed }
                            if (touching.isEmpty()) break
                            fingers = maxOf(fingers, touching.size)
                            if (touching.size >= 2) {
                                if (held) { mouse("up", cx, cy); held = false; pressed = false }
                                val z = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true) - Offset(originX, 0f)
                                zoomSpan *= z
                                moved += pan.getDistance()
                                if (multi == null) {
                                    multi = when {
                                        !vertical && abs(zoomSpan - 1f) > 0.06f -> "pinch"
                                        moved > slop * 2 -> "scroll"
                                        else -> null
                                    }
                                }
                                when (multi) {
                                    "pinch" -> {
                                        val nz = (zoom * z).coerceIn(1f, MAX_ZOOM)
                                        panX = (panX + centroid.x) * (nz / zoom) - centroid.x - pan.x
                                        panY = (panY + centroid.y) * (nz / zoom) - centroid.y - pan.y
                                        zoom = nz
                                        clampPan()
                                    }
                                    "scroll" -> if (!cursor) {
                                        // Zoomed in: move the view first, then scroll the page with what is left.
                                        val want = panY - pan.y
                                        val clamped = want.coerceIn(0f, (zoom - 1f) * hPx)
                                        panY = clamped
                                        panX = (panX - pan.x).coerceIn(0f, (zoom - 1f) * wPx)
                                        scrollAcc += (want - clamped) / zoom / hPx
                                        if (abs(scrollAcc) > 0.02f) {
                                            wheel(centroid.x, centroid.y, scrollAcc)
                                            scrollAcc = 0f
                                        }
                                    }
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            if (fingers > 1) continue // lifting the last of several fingers
                            val change = touching.first()
                            val delta = change.positionChange()
                            moved += delta.getDistance()
                            if (cursor) {
                                val speed = 1f + min(delta.getDistance() / 12f, 1.2f)
                                var nx = cx + delta.x * speed
                                var ny = cy + delta.y * speed
                                if (zoom > 1f) { // pushing the cursor against an edge moves the zoomed view
                                    if (nx < 0) panX += nx
                                    if (nx > wPx - 4) panX += nx - (wPx - 4)
                                    if (ny < 0) panY += ny
                                    if (ny > hPx - 4) panY += ny - (hPx - 4)
                                    clampPan()
                                }
                                cx = nx.coerceIn(0f, wPx - 4)
                                cy = ny.coerceIn(0f, hPx - 4)
                                if (again && !held && moved > slop) {
                                    mouse("down", cx, cy); held = true; pressed = true
                                }
                                if (delta != Offset.Zero) mouse("move", cx, cy, held = held)
                            } else if (!longFired) {
                                cx = (change.position.x - originX).coerceIn(0f, wPx); cy = change.position.y.coerceIn(0f, hPx)
                                if (moved > slop) {
                                    if (!held) { mouse("down", down.position.x, down.position.y); held = true; pressed = true }
                                    mouse("move", cx, cy, held = true)
                                }
                            }
                            change.consume()
                        }
                        val quick = end - start < 300
                        when {
                            fingers >= 2 -> if (cursor && multi == null && quick) mouse("click", cx, cy, button = "right")
                            held -> { mouse("up", cx, cy); pressed = false }
                            longFired -> {}
                            moved < slop -> {
                                if (again) {
                                    mouse("down", cx, cy, clicks = 2)
                                    mouse("up", cx, cy, clicks = 2, result = ::clickResult)
                                    lastTapAt = 0L
                                } else {
                                    mouse("click", cx, cy, result = ::clickResult)
                                    lastTapAt = start
                                    lastTapPos = down.position
                                }
                            }
                        }
                    }
                },
        ) {
          Box(Modifier.align(Alignment.TopCenter).size(fitW, fitH).background(Color.Black, RoundedCornerShape(14.dp))) {
            Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = "Live view of the tab",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = zoom
                        scaleY = zoom
                        translationX = -panX
                        translationY = -panY
                        clip = true
                    },
            )
            if (cursor || pressed) Cursor(cx, cy, pressed)
            if (cursor && !viewOnly) ScrollBar(current, hPx) { dyFraction -> wheel(cx, cy, dyFraction) }
            if (agentChat != null) {
                Surface(
                    onClick = { onOpenChat(agentChat.id) },
                    shape = RoundedCornerShape(50),
                    color = Color(0xE61D1B20),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(0xFFFF5A5A), CircleShape))
                        Text(
                            "View only · ${agentChat.title.ifBlank { "An agent" }} is using this tab",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 240.dp),
                        )
                        Text("Open chat", color = Color(0xFFB8C7FF), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (zoom > 1.01f) {
                Surface(
                    onClick = { zoom = 1f; panX = 0f; panY = 0f },
                    shape = CircleShape,
                    color = Color(0xCC1D1B20),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                ) {
                    Text(
                        "%.1f× · tap for 1×".format(zoom),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            if (streamError != null) {
                Text(
                    streamError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter).background(MaterialTheme.colorScheme.surface).padding(4.dp),
                )
            }
          }
        }
    }
}

/** The pointer, drawn on the phone (the browser's screencast has none). Tinted while a button is held. */
@Composable
private fun Cursor(x: Float, y: Float, pressed: Boolean) {
    val fill = if (pressed) MaterialTheme.colorScheme.primary else Color.White
    androidx.compose.foundation.Canvas(
        Modifier.offset { IntOffset((x - 3).roundToInt(), (y - 2).roundToInt()) }.size(22.dp),
    ) {
        val s = size.width / 24f
        val path = Path().apply {
            moveTo(4 * s, 2 * s); lineTo(4 * s, 19 * s); lineTo(8.5f * s, 15 * s); lineTo(11.5f * s, 21.5f * s)
            lineTo(14 * s, 20.4f * s); lineTo(11 * s, 14 * s); lineTo(17 * s, 14 * s); close()
        }
        drawPath(path, fill)
        drawPath(path, Color.Black, style = Stroke(width = 1.4f * s))
    }
}

/**
 * Cursor mode scrolls only with this bar. The thumb shows where the page is; dragging it scrolls the
 * page under the cursor, and tapping above or below the thumb moves a screen.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ScrollBar(frame: BrowserFrame, trackPx: Float, onScroll: (Float) -> Unit) {
    val density = LocalDensity.current
    val viewport = frame.viewportHeight.toFloat().coerceAtLeast(1f)
    val content = frame.contentHeight.coerceAtLeast(viewport)
    val thumbPx = (trackPx * viewport / content).coerceAtLeast(with(density) { 36.dp.toPx() }).coerceAtMost(trackPx)
    val maxScroll = (content - viewport).coerceAtLeast(1f)
    val thumbTop = (trackPx - thumbPx) * (frame.scrollY / maxScroll).coerceIn(0f, 1f)
    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(18.dp)
            .padding(vertical = 4.dp, horizontal = 3.dp)
            .background(Color(0x22000000), RoundedCornerShape(8.dp))
            .pointerInput(trackPx, thumbPx, thumbTop) {
                detectTapGestures { p -> onScroll(if (p.y < thumbTop) -0.9f else 0.9f) }
            }
            .pointerInput(trackPx, content, viewport) {
                var acc = 0f
                detectDragGestures(onDragEnd = { if (acc != 0f) onScroll(acc); acc = 0f }) { change, drag ->
                    change.consume()
                    // Thumb moved by drag.y pixels of the track = this much of the page, as a share of the viewport.
                    acc += drag.y * (content / trackPx) / viewport
                    if (abs(acc) > 0.03f) { onScroll(acc); acc = 0f }
                }
            },
    ) {
        Box(
            Modifier
                .offset { IntOffset(0, thumbTop.roundToInt()) }
                .fillMaxWidth()
                .height(with(density) { thumbPx.toDp() })
                .background(Color(0x77000000), RoundedCornerShape(6.dp)),
        )
    }
}

/** Keys and a text field for the focused page field. Typed text goes straight to the page and is never saved. */

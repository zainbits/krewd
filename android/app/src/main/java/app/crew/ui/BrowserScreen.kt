package app.crew.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.crew.Connection
import app.crew.CrewViewModel
import app.crew.R
import app.crew.UiState
import app.crew.constants.ShellDimensions
import app.crew.data.BrowserTab
import kotlinx.coroutines.delay
import org.json.JSONObject

private const val TAB_REFRESH_MS = 3_000L
private const val SCROLL_STEP = 0.04f // send a scroll after the finger moves 4% of the picture

/** The agents' browser: its open tabs. Tapping one shows it live. */
@Composable
fun BrowserScreen(state: UiState, viewModel: CrewViewModel, padding: PaddingValues) {
    val online = state.connection is Connection.Online
    LaunchedEffect(online) {
        while (online) {
            viewModel.refreshTabs()
            delay(TAB_REFRESH_MS)
        }
    }
    val browser = state.browser
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "intro") {
            Text(
                "The agents' own browser. Watch a tab live, or take over to sign in or enter a code: what you type goes straight to the page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        when {
            browser == null -> item(key = "loading") { CircularProgressIndicator(Modifier.padding(24.dp)) }
            !browser.available -> item(key = "down") {
                SectionCard {
                    Text("The browser is not running", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "It starts when the server's desktop is logged in. On the server: ./master.sh start browser",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            browser.tabs.isEmpty() -> item(key = "none") {
                Text("No open tabs.", modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(browser?.tabs.orEmpty(), key = { it.id }) { tab -> TabRow(tab) { viewModel.openTab(tab.id) } }
    }
}

@Composable
private fun TabRow(tab: BrowserTab, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(ShellDimensions.CARD_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_web_24), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(tab.title.ifBlank { tab.host }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tab.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** One tab, live. Tap to click, drag to scroll, and type below to fill the focused field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserViewer(state: UiState, viewModel: CrewViewModel) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val tab = state.openTab
    // Not saveable on purpose: typed text (maybe a password) must not land in saved instance state.
    var text by remember { mutableStateOf("") }
    var hidden by remember { mutableStateOf(false) }

    fun send(vararg pairs: Pair<String, Any>) = viewModel.tabInput(JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } })

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ShellDimensions.TITLE_PILL_START_MARGIN).padding(top = ShellDimensions.TITLE_PILL_TOP_GAP, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FloatingPill {
                IconButton(onClick = viewModel::closeTab) { Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Close the live view") }
            }
            FloatingPill(Modifier.weight(1f)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(tab?.title?.ifBlank { null } ?: "Browser", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (tab != null) Text(tab.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            FloatingPill {
                Row {
                    IconButton(onClick = { send("type" to "navigate", "action" to "back") }) { Icon(painterResource(R.drawable.ic_chevron_left_24), contentDescription = "Back") }
                    IconButton(onClick = { send("type" to "navigate", "action" to "forward") }) { Icon(painterResource(R.drawable.ic_chevron_right_24), contentDescription = "Forward") }
                    IconButton(onClick = { send("type" to "navigate", "action" to "reload") }) { Icon(painterResource(R.drawable.ic_refresh_24), contentDescription = "Reload") }
                }
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            val current = frame
            if (current == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(state.streamError ?: "Connecting to the tab…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val ratio = current.bitmap.width.toFloat() / current.bitmap.height
                val boxRatio = constraints.maxWidth.toFloat() / constraints.maxHeight
                val width = if (ratio > boxRatio) maxWidth else maxHeight * ratio
                val height = if (ratio > boxRatio) maxWidth / ratio else maxHeight
                var pending by remember { mutableFloatStateOf(0f) }
                Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = "Live view of the tab",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .size(width, height)
                        .pointerInput(Unit) {
                            detectTapGestures { p -> send("type" to "tap", "x" to p.x / size.width, "y" to p.y / size.height) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = {
                                    if (pending != 0f) send("type" to "scroll", "x" to 0.5, "y" to 0.5, "dx" to 0, "dy" to pending)
                                    pending = 0f
                                },
                            ) { change, drag ->
                                change.consume()
                                // Finger up scrolls the page down, like touch scrolling.
                                pending += -drag.y / size.height
                                if (kotlin.math.abs(pending) >= SCROLL_STEP) {
                                    send("type" to "scroll", "x" to change.position.x / size.width, "y" to change.position.y / size.height, "dx" to 0, "dy" to pending)
                                    pending = 0f
                                }
                            }
                        },
                )
                if (state.streamError != null) {
                    Text(
                        state.streamError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.TopCenter).background(MaterialTheme.colorScheme.surface).padding(4.dp),
                    )
                }
            }
        }

        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(ShellDimensions.COMPOSER_MARGIN),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Enter", "Backspace", "Tab", "Escape", "PageDown", "PageUp").forEach { key ->
                    AssistChip(onClick = { send("type" to "key", "key" to key) }, label = { Text(keyLabel(key)) })
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Type into the page (tap a field first)") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (hidden) KeyboardType.Password else KeyboardType.Text,
                    autoCorrectEnabled = !hidden,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = {
                    if (text.isNotEmpty()) send("type" to "text", "text" to text)
                    text = ""
                }),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { hidden = !hidden }) { Text(if (hidden) "Show" else "Hide") }
                        IconButton(onClick = {
                            if (text.isNotEmpty()) send("type" to "text", "text" to text)
                            text = ""
                        }, enabled = text.isNotEmpty()) { Icon(painterResource(R.drawable.ic_send_24), contentDescription = "Type it") }
                    }
                },
            )
            Text(
                "Typed text goes straight to the page. Crew does not save it and the agent does not see it in the chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

private fun keyLabel(key: String) = when (key) {
    "Backspace" -> "Delete"
    "Escape" -> "Esc"
    "PageDown" -> "Page down"
    "PageUp" -> "Page up"
    else -> key
}

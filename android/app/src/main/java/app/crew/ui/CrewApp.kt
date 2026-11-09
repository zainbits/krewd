package app.crew.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import app.crew.Connection
import app.crew.CrewViewModel
import app.crew.NavTab
import app.crew.R
import app.crew.constants.LiquidNavDimensions
import app.crew.constants.ShellDimensions
import app.crew.liquidnav.LiquidNavBar
import app.crew.liquidnav.LiquidNavItem

private enum class Tab(val title: String, val label: String, val icon: Int) {
    Tasks("Crew", "Tasks", R.drawable.ic_chat_24),
    Browser("Browser", "Browser", R.drawable.ic_web_24),
    Approvals("Approvals", "Approvals", R.drawable.ic_approval_24),
    Settings("Settings", "Settings", R.drawable.ic_tune_24),
}

private val navItems = Tab.entries.map { LiquidNavItem(it.label, it.icon) }

@Composable
fun CrewApp(viewModel: CrewViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tabIndex = state.navTab
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.dismissMessage()
        }
    }
    LaunchedEffect(state.connection) {
        if (state.connection == Connection.NotSetUp) viewModel.navigate(NavTab.SETTINGS)
    }
    BackHandler(enabled = state.openSessionId != null) { viewModel.closeSession() }
    BackHandler(enabled = state.openSessionId == null && tabIndex != NavTab.TASKS) { viewModel.navigate(NavTab.TASKS) }

    state.confirmStop?.let { id ->
        AlertDialog(
            onDismissRequest = { viewModel.answerStop(false) },
            title = { Text("Stop this task?") },
            text = { Text(state.sessions.firstOrNull { it.id == id }?.title ?: "The running turn ends and queued messages are dropped.") },
            confirmButton = { TextButton(onClick = { viewModel.answerStop(true) }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { viewModel.answerStop(false) }) { Text("Keep running") } },
        )
    }

    // Depth: 0 tabs, 1 a task's chat.
    val depth = if (state.openSessionId != null) 1 else 0
    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = depth,
        transitionSpec = {
            if (targetState > initialState) {
                (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "chat",
    ) { level ->
        if (level == 1) {
            ChatScreen(state, viewModel)
        } else {
            val tab = Tab.entries[tabIndex]
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                val padding = shellContentPadding()
                when (tab) {
                    Tab.Tasks -> TasksScreen(state, viewModel, padding, onOpenSettings = { viewModel.navigate(NavTab.SETTINGS) })
                    Tab.Browser -> BrowserScreen(state, viewModel, landscape)
                    Tab.Approvals -> ApprovalsScreen(state, viewModel, padding)
                    Tab.Settings -> SettingsScreen(state, viewModel, padding)
                }
                if (tab != Tab.Browser) TitlePill(
                    title = if (tab == Tab.Approvals && state.approvals.isNotEmpty()) "Approvals · ${state.approvals.size}" else tab.title,
                    connection = state.connection,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(
                            WindowInsets.statusBars.union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        )
                        .padding(start = ShellDimensions.TITLE_PILL_START_MARGIN, top = ShellDimensions.TITLE_PILL_TOP_GAP),
                )
                if (!(tab == Tab.Browser && landscape)) LiquidNavBar(
                    items = navItems,
                    selectedIndex = tabIndex,
                    onSelect = { viewModel.navigate(it) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        )
                        .padding(
                            start = LiquidNavDimensions.SIDE_MARGIN,
                            end = LiquidNavDimensions.SIDE_MARGIN,
                            bottom = LiquidNavDimensions.BOTTOM_GAP,
                        ),
                )
            }
        }
    }
    state.pendingDelete?.let { deleted ->
        UndoPill(
            text = "Deleted \u201c${deleted.title}\u201d",
            onUndo = viewModel::undoDelete,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = LiquidNavDimensions.BOTTOM_GAP + LiquidNavDimensions.BAR_HEIGHT + 12.dp, start = 16.dp, end = 16.dp),
        )
    }
    if (state.restarting) {
        RestartingPill(
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = ShellDimensions.TITLE_PILL_TOP_GAP + ShellDimensions.TITLE_PILL_HEIGHT + 8.dp),
        )
    }
    }
}

/** "Deleted" with Undo, for the 3 seconds before a delete reaches the server. */
@Composable
private fun UndoPill(text: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    FloatingPill(modifier) {
        Row(
            Modifier.padding(start = 20.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            TextButton(onClick = onUndo) { Text("Undo") }
        }
    }
}

/** While the server restarts: a pill at the top until it answers again (the chat history is on the server). */
@Composable
private fun RestartingPill(modifier: Modifier = Modifier) {
    FloatingPill(modifier) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Restarting Crew… reconnecting", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Content scrolls edge to edge behind the title pill and the nav bar, with room to clear both. */
@Composable
private fun shellContentPadding(): PaddingValues {
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    val dir = LocalLayoutDirection.current
    return PaddingValues(
        start = bars.calculateStartPadding(dir) + ShellDimensions.CONTENT_HORIZONTAL_PADDING,
        end = bars.calculateEndPadding(dir) + ShellDimensions.CONTENT_HORIZONTAL_PADDING,
        top = bars.calculateTopPadding() + ShellDimensions.TITLE_PILL_TOP_GAP +
            ShellDimensions.TITLE_PILL_HEIGHT + ShellDimensions.CONTENT_CLEARANCE,
        bottom = bars.calculateBottomPadding() + LiquidNavDimensions.BOTTOM_GAP +
            LiquidNavDimensions.BAR_HEIGHT + ShellDimensions.CONTENT_CLEARANCE,
    )
}

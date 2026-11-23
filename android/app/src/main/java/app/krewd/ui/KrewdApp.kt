package app.krewd.ui

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import app.krewd.Connection
import app.krewd.KrewdViewModel
import app.krewd.NavTab
import app.krewd.R
import app.krewd.constants.ShellDimensions
import io.github.zainbits.cohesion.GlassMode
import io.github.zainbits.cohesion.ProvideGlass
import io.github.zainbits.cohesion.glassSource
import io.github.zainbits.cohesion.rememberGlassBackdrop
import io.github.zainbits.cohesion.rememberGlassThrottled
import io.github.zainbits.meniscus.MeniscusGlass
import io.github.zainbits.meniscus.MeniscusNavBar
import io.github.zainbits.meniscus.MeniscusNavBarDefaults
import io.github.zainbits.meniscus.MeniscusNavItem

private enum class Tab(val title: String, val label: String, val icon: Int) {
    Tasks("Crew", "Tasks", R.drawable.ic_chat_24),
    Routines("Routines", "Routines", R.drawable.ic_schedule_24),
    Browser("Browser", "Browser", R.drawable.ic_web_24),
    Settings("Settings", "Settings", R.drawable.ic_tune_24),
}

private val navItems = Tab.entries.map { MeniscusNavItem(it.label, it.icon) }

@Composable
fun KrewdApp(viewModel: KrewdViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tabIndex = state.navTab
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    // Each list's place lives here, above the screens: opening a chat disposes the screen under it, and going back
    // must land where you were, not at the top.
    val tasksList = rememberLazyListState()
    val routinesList = rememberLazyListState()
    val settingsList = rememberLazyListState()
    val routineList = rememberSaveable(state.openRoutineId, saver = LazyListState.Saver) { LazyListState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.dismissMessage()
        }
    }
    LaunchedEffect(state.connection) {
        if (state.connection == Connection.NotSetUp) viewModel.navigate(NavTab.SETTINGS)
    }
    BackHandler(enabled = state.openSessionId == null && state.openRoutineId == null && tabIndex != NavTab.TASKS) { viewModel.navigate(NavTab.TASKS) }
    BackHandler(enabled = state.openSessionId == null && state.openRoutineId != null) { viewModel.closeRoutine() }
    BackHandler(enabled = state.openSessionId != null) { viewModel.closeSession() }

    state.confirmStop?.let { id ->
        AlertDialog(
            onDismissRequest = { viewModel.answerStop(false) },
            title = { Text("Stop this task?") },
            text = { Text(state.sessions.firstOrNull { it.id == id }?.title ?: "The running turn ends and queued messages are dropped.") },
            confirmButton = { TextButton(onClick = { viewModel.answerStop(true) }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { viewModel.answerStop(false) }) { Text("Keep running") } },
        )
    }

    // Depth: 0 tabs, 1 a routine's page, 2 a task's chat (from a routine's page, back returns to it).
    val depth = when {
        state.openSessionId != null -> 2
        state.openRoutineId != null -> 1
        else -> 0
    }
    // Liquid glass: the Settings switch, off by itself in battery saver or heat (then Settings shows it off).
    val glassThrottled = rememberGlassThrottled()
    val darkGlass = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val glass = KrewdGlass(on = state.glassEnabled && !glassThrottled, throttled = glassThrottled, clarity = state.glassClarity, dark = darkGlass)
    // The tab screens are what the nav bar's glass (Meniscus) shows through.
    val tabsBackdrop = rememberGlassBackdrop()
    val homeAsk = rememberHomeAsk()
    // In-window menus (glass over the screen behind them) open here, over everything.
    val menuHost = remember { GlassMenuHost() }
    val navGlass = remember(tabsBackdrop, glass.on) { if (glass.on) MeniscusGlass(tabsBackdrop) else null }
    CompositionLocalProvider(
        // The theme's text colour everywhere, glass included (white on dark, near-black on light).
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        LocalCodeWrap provides CodeWrap(state.codeWrap, viewModel::setCodeWrap),
        LocalKrewdGlass provides glass,
        LocalKrewdBackdrop provides if (glass.on) tabsBackdrop else null,
        LocalGlassMenuHost provides menuHost,
    ) {
    ProvideGlass(
        clarity = state.glassClarity,
        dark = darkGlass,
        mode = GlassMode.Full,
    ) {
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
        if (level == 2) {
            ChatScreen(state, viewModel)
        } else if (level == 1) {
            RoutineScreen(state, viewModel, routineList)
        } else {
            val tab = Tab.entries[tabIndex]
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                // The Krewd tab's page starts level with the title (its connection status sits at the top right of
                // the page, beside the title) and has the Ask Krewd bar above the nav bar.
                val padding = if (tab == Tab.Tasks) shellContentPadding(titlePill = false, extraBottom = ASK_BAR_HEIGHT + ASK_BAR_GAP) else shellContentPadding()
                // The glass (nav bar, title) shows the tab screens. Not the browser: its page never passes under the
                // glass and changes with every video frame, so it is drawn outside the recording, which then holds
                // only the plain background (the same look for the nav bar, at no cost).
                Box(Modifier.fillMaxSize().then(if (glass.on) Modifier.glassSource(tabsBackdrop) else Modifier).background(MaterialTheme.colorScheme.surface)) {
                    when (tab) {
                        Tab.Tasks -> TasksScreen(state, viewModel, padding, tasksList, homeAsk, onOpenSettings = { viewModel.navigate(NavTab.SETTINGS) })
                        Tab.Routines -> RoutinesScreen(state, viewModel, padding, routinesList)
                        Tab.Browser -> {}
                        Tab.Settings -> SettingsScreen(state, viewModel, padding, settingsList)
                    }
                }
                if (tab == Tab.Browser) BrowserScreen(state, viewModel, landscape)
                // Outside the glass source, so it can be glass over the tasks.
                if (tab == Tab.Tasks) HomeAskBar(state, viewModel, homeAsk)
                if (tab != Tab.Browser) TitlePill(
                    title = if (tab == Tab.Tasks) "Krewd" else tab.title,
                    wordmark = tab == Tab.Tasks,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(
                            WindowInsets.statusBars.union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        )
                        .padding(start = ShellDimensions.TITLE_PILL_START_MARGIN, top = ShellDimensions.TITLE_PILL_TOP_GAP),
                )
                if (!(tab == Tab.Browser && landscape)) MeniscusNavBar(
                    items = navItems,
                    selectedIndex = tabIndex,
                    onSelect = { viewModel.navigate(it) },
                    glass = navGlass,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        )
                        .padding(
                            start = MeniscusNavBarDefaults.SideMargin,
                            end = MeniscusNavBarDefaults.SideMargin,
                            bottom = MeniscusNavBarDefaults.BottomGap,
                        ),
                )
                // The new task: a glass sheet over the Krewd tab, outside its glass source and over the nav bar.
                if (tab == Tab.Tasks && homeAsk.asking) NewTaskSheet(
                    state, viewModel, homeAsk.engine,
                    onDismiss = { homeAsk.asking = false },
                    onOpenSettings = { viewModel.navigate(NavTab.SETTINGS) },
                    inWindow = true,
                    backdrop = if (glass.on) tabsBackdrop else null,
                )
            }
        }
    }
    state.pendingDelete?.let { deleted ->
        UndoPill(
            text = "Deleted \u201c${deleted.title}\u201d",
            onUndo = viewModel::undoDelete,
            glass = depth == 0,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(
                    bottom = MeniscusNavBarDefaults.BottomGap + MeniscusNavBarDefaults.Height + 12.dp +
                        if (state.openSessionId == null && tabIndex == NavTab.TASKS) ASK_BAR_HEIGHT + ASK_BAR_GAP else 0.dp,
                    start = 16.dp,
                    end = 16.dp,
                ),
        )
    }
    if (state.restarting) {
        RestartingPill(
            glass = depth == 0,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = ShellDimensions.TITLE_PILL_TOP_GAP + ShellDimensions.TITLE_PILL_HEIGHT + 8.dp),
        )
    }
    GlassMenuLayer(menuHost)
    }
    }
    }
}

/** "Deleted" with Undo, for the 3 seconds before a delete reaches the server. */
@Composable
private fun UndoPill(text: String, onUndo: () -> Unit, glass: Boolean, modifier: Modifier = Modifier) {
    KrewdPill(modifier, glass = glass) {
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
private fun RestartingPill(glass: Boolean, modifier: Modifier = Modifier) {
    KrewdPill(modifier, glass = glass) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Restarting Krewd… reconnecting", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Content scrolls edge to edge behind the title pill and the nav bar, with room to clear both. */
@Composable
private fun shellContentPadding(titlePill: Boolean = true, extraBottom: androidx.compose.ui.unit.Dp = 0.dp): PaddingValues {
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    val dir = LocalLayoutDirection.current
    return PaddingValues(
        start = bars.calculateStartPadding(dir) + ShellDimensions.CONTENT_HORIZONTAL_PADDING,
        end = bars.calculateEndPadding(dir) + ShellDimensions.CONTENT_HORIZONTAL_PADDING,
        top = bars.calculateTopPadding() + ShellDimensions.TITLE_PILL_TOP_GAP +
            (if (titlePill) ShellDimensions.TITLE_PILL_HEIGHT + ShellDimensions.CONTENT_CLEARANCE else 0.dp),
        bottom = bars.calculateBottomPadding() + MeniscusNavBarDefaults.BottomGap +
            MeniscusNavBarDefaults.Height + ShellDimensions.CONTENT_CLEARANCE + extraBottom,
    )
}

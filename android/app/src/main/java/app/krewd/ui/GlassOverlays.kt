package app.krewd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.zainbits.cohesion.GlassBackdrop
import io.github.zainbits.vitreous.GlassShape
import io.github.zainbits.vitreous.GlassSurface
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Krewd's floating sheets and menus, drawn in the app's own window so they can be liquid glass over the screen
 * behind them (a system bottom sheet or dropdown is a separate window that glass cannot see through). With the
 * glass off they are plain Material cards in the same places.
 */

/** True inside a glass sheet or menu: controls there draw their own solid backgrounds so they read over the glass. */
val LocalOnGlass = compositionLocalOf { false }

/** Closes a [GlassSheet] with its animation; [close] then calls the sheet's onDismiss. */
@Stable
class GlassSheetState {
    internal var closing by mutableStateOf(false)
    fun close() { closing = true }
}

@Composable
fun rememberGlassSheetState(): GlassSheetState = remember { GlassSheetState() }

/**
 * A sheet that floats above the bottom of the screen: liquid glass over [backdrop] (plain while the glass is
 * off or [backdrop] is null). Drag its top down, tap outside or press Back to close. Put it outside the glass
 * source it shows, as the last child of the screen.
 */
@Composable
fun GlassSheet(
    backdrop: GlassBackdrop?,
    onDismiss: () -> Unit,
    state: GlassSheetState = rememberGlassSheetState(),
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shown = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    LaunchedEffect(state.closing) {
        if (state.closing) {
            shown.animateTo(0f, tween(180))
            onDismiss()
        } else {
            shown.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 520f))
        }
    }
    BackHandler { state.close() }
    val glassOn = LocalKrewdGlass.current.on && backdrop != null
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = shown.value }
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.close() },
        )
        Box(
            modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 24.dp)
                .graphicsLayer {
                    translationY = (1f - shown.value) * (size.height + 48.dp.toPx()) + drag.value
                },
        ) {
            val body: @Composable () -> Unit = {
                CompositionLocalProvider(LocalOnGlass provides true) {
                    Column(Modifier.fillMaxWidth()) {
                        // The handle: drag it (or the space around it) down to close.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .draggable(
                                    orientation = Orientation.Vertical,
                                    state = rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                                    onDragStopped = { velocity ->
                                        if (drag.value > with(density) { 120.dp.toPx() } || velocity > 1800f) state.close()
                                        else scope.launch { drag.animateTo(0f) }
                                    },
                                )
                                .padding(top = 10.dp, bottom = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(width = 36.dp, height = 4.dp).background(LocalContentColor.current.copy(alpha = 0.35f), CircleShape))
                        }
                        content()
                    }
                }
            }
            if (glassOn) {
                GlassSurface(backdrop = backdrop, modifier = Modifier.fillMaxWidth(), shape = GlassShape.rounded(32.dp)) { body() }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 6.dp,
                ) { body() }
            }
        }
    }
}

/** One open in-window menu: its anchor in window pixels, what it shows through, and its rows. */
internal class MenuSpec(
    val anchor: Rect,
    val backdrop: GlassBackdrop?,
    val width: Dp,
    val onDismiss: () -> Unit,
    val content: @Composable ColumnScope.() -> Unit,
)

/** Where in-window menus open; [GlassMenuLayer] draws the open one. One per window, at its root. */
@Stable
class GlassMenuHost {
    internal var open by mutableStateOf<MenuSpec?>(null)
}

val LocalGlassMenuHost = compositionLocalOf<GlassMenuHost?> { null }

/** Draws the open menu of [host] over everything: put it last in the window's root Box (it fills the window). */
@Composable
fun GlassMenuLayer(host: GlassMenuHost) {
    val spec = host.open ?: return
    BackHandler { spec.onDismiss() }
    val glassOn = LocalKrewdGlass.current.on && spec.backdrop != null
    val density = LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    val top = WindowInsets.statusBars.getTop(density) + margin
    val bottom = WindowInsets.navigationBars.getBottom(density) + margin
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { spec.onDismiss() },
        )
        Layout(
            content = {
                val body: @Composable () -> Unit = {
                    CompositionLocalProvider(LocalOnGlass provides true) {
                        Column(Modifier.padding(vertical = 8.dp), content = spec.content)
                    }
                }
                if (glassOn) {
                    GlassSurface(backdrop = spec.backdrop, modifier = Modifier.width(spec.width), shape = GlassShape.rounded(22.dp)) { body() }
                } else {
                    Surface(
                        modifier = Modifier.width(spec.width),
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shadowElevation = 3.dp,
                    ) { body() }
                }
            },
            // The layer fills the window from its origin, so the anchor's window pixels are this layout's pixels.
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val card = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = (constraints.maxHeight - top - bottom).coerceAtLeast(0)))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeMenu(card, spec.anchor, constraints.maxWidth, constraints.maxHeight, margin, top, bottom)
            }
        }
    }
}

/** Below the anchor (above it when there is no room), lined up with its right edge on the right half, else its left. */
private fun androidx.compose.ui.layout.Placeable.PlacementScope.placeMenu(
    card: androidx.compose.ui.layout.Placeable, anchor: Rect, width: Int, height: Int, margin: Int, top: Int, bottom: Int,
) {
    val rightHalf = anchor.center.x > width / 2f
    val x = (if (rightHalf) anchor.right.roundToInt() - card.width else anchor.left.roundToInt())
        .coerceIn(margin, (width - card.width - margin).coerceAtLeast(margin))
    val below = anchor.bottom.roundToInt() + margin
    val y = if (below + card.height <= height - bottom) below
    else (anchor.top.roundToInt() - margin - card.height).coerceAtLeast(top)
    card.place(IntOffset(x, y))
}

/**
 * A menu for the element it sits in (like Material's DropdownMenu): a glass card over [backdrop] in the app's
 * window when a [GlassMenuHost] is there. Only use it outside dialogs and system sheets (they are other windows).
 */
@Composable
fun KrewdMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    backdrop: GlassBackdrop?,
    width: Dp = 248.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalGlassMenuHost.current
    var anchor by remember { mutableStateOf<Rect?>(null) }
    // A point with no size that reports where its parent (the element the menu belongs to) is in the window.
    Layout(
        modifier = Modifier.onGloballyPositioned { c -> anchor = (c.parentLayoutCoordinates ?: c).boundsInWindow() },
    ) { _, _ -> layout(0, 0) {} }
    if (host == null) {
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) { content() }
        return
    }
    val at = anchor
    if (expanded && at != null) {
        val spec = remember(at, backdrop, width) { MenuSpec(at, backdrop, width, onDismiss, content) }
        DisposableEffect(spec) {
            host.open = spec
            onDispose { if (host.open === spec) host.open = null }
        }
    }
}

/** A row of a [KrewdMenu] (or a menu card): its label, a quiet hint under it, in the card's ink. */
@Composable
fun KrewdMenuItem(label: String, hint: String? = null, color: Color = Color.Unspecified, checked: Boolean? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val ink = LocalContentColor.current
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null) Text(if (checked) "✓" else "", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (color != Color.Unspecified) color else if (enabled) ink else ink.copy(alpha = 0.4f))
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.7f)) }
        }
    }
}

/** A quiet heading inside a menu card. */
@Composable
fun KrewdMenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = LocalContentColor.current.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
    )
}

/** A thin line between groups in a menu card. */
@Composable
fun KrewdMenuDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(LocalContentColor.current.copy(alpha = 0.15f)))
}

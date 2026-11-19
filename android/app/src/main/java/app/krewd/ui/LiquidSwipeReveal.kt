package app.krewd.ui

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.krewd.constants.ShellDimensions
import io.github.zainbits.cohesion.GlassMode
import io.github.zainbits.cohesion.GlassPullSpec
import io.github.zainbits.cohesion.GlassStyle
import io.github.zainbits.cohesion.glassPullContent
import io.github.zainbits.cohesion.glassPullGesture
import io.github.zainbits.cohesion.rememberGlassPullState
import io.github.zainbits.vitreous.GlassGroupState
import io.github.zainbits.vitreous.GlassShape
import io.github.zainbits.vitreous.glassGroup
import io.github.zainbits.vitreous.glassPart
import io.github.zainbits.vitreous.rememberGlassGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

private val TILE_MAX_WIDTH = 64.dp
private val TILE_GAP = 8.dp
private val TILE_ICON = 22.dp

/** Same liquid as the chat's back button and title: closer than half of this, two pieces are one. */
private val TILE_MERGE = 10.dp
private val TILE_PULL = GlassPullSpec(maxPull = 12.dp)
private const val OVERDRAG_RESISTANCE = 0.5f
private const val OVERDRAG_LIMIT = 1.6f
private const val FLING_VELOCITY = 1500f

/**
 * Slide a row left and the card divides: its end splits off as Delete (or Stop), then Pin, tiles of the same
 * card on the same level, joined by a liquid neck that thins and pinches off (a haptic tick each). Sliding back
 * merges them into the card the same way. It is one Cohesion group drawn flat (no glass, no offscreen pass), so
 * the necks are the same smooth merge as the glass top bar's, and with the glass switch on the tiles stretch
 * under the finger like the app's other buttons. Releasing past half the reveal keeps them out; a tap on a tile
 * calls it with a function that slides the row back. TalkBack gets both as custom actions on the row.
 *
 * @param cardCorner the card's largest right-hand corner, so the shape behind it never shows past its edge.
 */
@Composable
internal fun LiquidSwipeReveal(
    pinned: Boolean,
    onPin: (close: () -> Unit) -> Unit,
    onDelete: (close: () -> Unit) -> Unit,
    @DrawableRes pinIcon: Int,
    @DrawableRes deleteIcon: Int,
    deleteLabel: String = "Delete",
    cardColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    cardCorner: Dp = ShellDimensions.CARD_CORNER,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val view = LocalView.current
    val stretchy = LocalKrewdGlass.current.on
    // The card's offset. Drags write it straight away; only the settle after a release animates it, and a new
    // drag cancels that settle, so a late drag step can never cancel the spring back (which left the card stuck).
    var offset by remember { mutableFloatStateOf(0f) }
    val settling = remember { arrayOfNulls<Job>(1) }
    var tilePx by remember { mutableFloatStateOf(0f) }
    val gapPx = with(density) { TILE_GAP.toPx() }
    val revealPx = SwipeTiles.reveal(tilePx, gapPx)
    val scope = rememberCoroutineScope()
    val settle = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
    val shut = spring<Float>(dampingRatio = 0.85f, stiffness = 700f)
    fun settleTo(target: Float, spec: AnimationSpec<Float>, velocity: Float = 0f) {
        settling[0]?.cancel()
        settling[0] = scope.launch { animate(offset, target, velocity, spec) { value, _ -> offset = value } }
    }
    val close: () -> Unit = { settleTo(0f, shut) }
    val isOpen by remember(revealPx) { derivedStateOf { revealPx > 0f && offset < -revealPx / 2f } }
    // At rest the row is only its card: no tiles and no shape behind it, so a still list draws nothing extra.
    val active by remember { derivedStateOf { offset != 0f } }
    val group = rememberGlassGroup()
    val style = remember(cardColor) { GlassStyle(solidColor = cardColor) }

    // A tick each time a tile pinches off the card (the neck snaps at half the merge distance).
    LaunchedEffect(gapPx) {
        val tiles = FloatArray(SwipeTiles.SIZE)
        val pinch = with(density) { TILE_MERGE.toPx() } / 2f
        var lastPin = 0f
        var lastDelete = 0f
        snapshotFlow { offset }.collect { o ->
            if (tilePx <= 0f) return@collect
            SwipeTiles.place(-o, 0f, tilePx, gapPx, tiles)
            val pin = tiles[SwipeTiles.PIN_GAP]
            val delete = tiles[SwipeTiles.DELETE_GAP]
            if ((lastDelete <= pinch && delete > pinch) || (lastPin <= pinch && pin > pinch)) {
                view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
            }
            lastPin = pin
            lastDelete = delete
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .onSizeChanged { size ->
                tilePx = min(with(density) { TILE_MAX_WIDTH.toPx() }, size.height.toFloat())
            }
            .glassGroup(backdrop = null, group = group, style = style, mode = GlassMode.Solid, merge = TILE_MERGE),
    ) {
        if (active) {
            SwipeTile(
                index = 0, slide = { -offset }, tilePx = tilePx, gapPx = gapPx, group = group, stretchy = stretchy,
                icon = pinIcon, label = if (pinned) "Unpin" else "Pin", tint = colors.primary, enabled = isOpen,
                onClick = { onPin(close) },
            )
            SwipeTile(
                index = 1, slide = { -offset }, tilePx = tilePx, gapPx = gapPx, group = group, stretchy = stretchy,
                icon = deleteIcon, label = deleteLabel, tint = colors.error, enabled = isOpen,
                onClick = { onDelete(close) },
            )
        }
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(if (pinned) "Unpin" else "Pin") { onPin(close); true },
                        CustomAccessibilityAction(deleteLabel) { onDelete(close); true },
                    )
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        // Past the full reveal the card pulls like a rubber band: heavier the further it goes.
                        val over = (-offset - revealPx).coerceAtLeast(0f)
                        val room = revealPx * (OVERDRAG_LIMIT - 1f)
                        val give = if (delta < 0f && over > 0f) OVERDRAG_RESISTANCE * (1f - over / room).coerceIn(0f, 1f) else 1f
                        offset = (offset + delta * give).coerceIn(-revealPx * OVERDRAG_LIMIT, 0f)
                    },
                    onDragStarted = { settling[0]?.cancel() },
                    onDragStopped = { velocity ->
                        val open = velocity < -FLING_VELOCITY || (offset < -revealPx / 2f && velocity < FLING_VELOCITY)
                        settleTo(if (open) -revealPx else 0f, if (open) settle else shut, velocity)
                    },
                )
                // Last in the chain, so switching it on and off never disturbs the drag above.
                .then(if (active) Modifier.glassPart(group, GlassShape.rounded(cardCorner)) else Modifier),
        ) { content() }
    }
}

/** One tile: a piece of the group sized and placed from the slide in the layout pass (no recomposition). */
@Composable
private fun BoxScope.SwipeTile(
    index: Int,
    slide: () -> Float,
    tilePx: Float,
    gapPx: Float,
    group: GlassGroupState,
    stretchy: Boolean,
    @DrawableRes icon: Int,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val pull = rememberGlassPullState(TILE_PULL)
    val tiles = remember { FloatArray(SwipeTiles.SIZE) }
    val shape = RoundedCornerShape(ShellDimensions.CARD_CORNER)
    Box(
        Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                // The row's height comes from the card alone: when the row measures intrinsics the tile is 0 x 0.
                val rowW = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
                val rowH = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
                SwipeTiles.place(slide(), rowW.toFloat(), tilePx, gapPx, tiles)
                val x = tiles[index * 2]
                val w = tiles[index * 2 + 1].roundToInt().coerceIn(0, rowW)
                val placeable = measurable.measure(Constraints.fixed(w, rowH))
                layout(rowW, rowH) { placeable.place(x.roundToInt(), 0) }
            }
            .glassPart(group, GlassShape.rounded(ShellDimensions.CARD_CORNER), if (stretchy) pull else null)
            .then(
                if (stretchy) {
                    Modifier.glassPullGesture(pull, enabled, onTap = onClick)
                } else {
                    Modifier.clip(shape).clickable(enabled = enabled, onClick = onClick)
                },
            )
            .semantics {
                role = Role.Button
                contentDescription = label
                if (enabled) onClick(label = label) { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .then(if (stretchy) Modifier.glassPullContent(pull) else Modifier)
                .graphicsLayer {
                    // The icon appears as the tile grows to full width, before it pinches off.
                    val e = if (tilePx > 0f) ((size.width / tilePx - 0.45f) / 0.55f).coerceIn(0f, 1f) else 0f
                    alpha = e * e
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(TILE_ICON))
        }
    }
}

/**
 * Where the tiles are when the card has slid [SwipeTiles.place]'s `slide` pixels left. The slid-out strip at the
 * row's end is shared out in two stages: Delete grows out of the card's end, then the neck between them opens and
 * pinches; then Pin grows between the card and Delete and pinches off the same way. Past the full reveal the
 * extra widens both tiles (the rubber band). Plain numbers, so it is tested on the JVM.
 */
internal object SwipeTiles {
    const val SIZE = 6
    const val PIN_GAP = 4
    const val DELETE_GAP = 5

    /** How far the card slides to show both tiles at full size. */
    fun reveal(tile: Float, gap: Float): Float = 2f * (tile + gap)

    /**
     * Fills [out] with Pin's left and width, Delete's left and width (pixels from the row's left edge in a row
     * [width] wide), then the card–Pin gap ([PIN_GAP]) and the Pin–Delete gap ([DELETE_GAP]).
     */
    fun place(slide: Float, width: Float, tile: Float, gap: Float, out: FloatArray) {
        val s = slide.coerceAtLeast(0f)
        val extra = (s - reveal(tile, gap)).coerceAtLeast(0f)
        val base = s - extra
        val first = min(base, tile + gap)
        var delete = min(first, tile)
        val deleteGap = first - delete
        val second = base - first
        var pin = min(second, tile)
        val pinGap = second - pin
        delete += extra / 2f
        pin += extra / 2f
        val deleteLeft = width - delete
        out[0] = deleteLeft - deleteGap - pin
        out[1] = pin
        out[2] = deleteLeft
        out[3] = delete
        out[PIN_GAP] = pinGap
        out[DELETE_GAP] = deleteGap
    }
}

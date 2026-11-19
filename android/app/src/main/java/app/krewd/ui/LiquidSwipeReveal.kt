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
import kotlin.math.sqrt
import kotlin.math.ceil
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import androidx.compose.ui.MotionDurationScale
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.MutableLongState
import kotlin.math.roundToInt

private val TILE_MAX_WIDTH = 64.dp
private val TILE_GAP = 12.dp
private val TILE_ICON = 22.dp

/**
 * How close a drop and the card (or two drops) melt together: a neck joins them until they are half of this
 * apart, so the neck snaps at the moment the drop pops ([BREAK_GAP]). The resting gap is wider, so open
 * tiles stand apart.
 */
private val TILE_MERGE = 16.dp
private val BREAK_GAP = 8.dp
private val JOIN_GAP = 2.dp
private val TILE_PULL = GlassPullSpec(maxPull = 12.dp)
private const val OVERDRAG_RESISTANCE = 0.5f
private const val OVERDRAG_LIMIT = 1.6f
private const val FLING_VELOCITY = 1500f

/**
 * Slide a row left and the drops come out of the card's moving end: the card slides, its end bulges, a dip
 * forms where a drop is pulled out, then the neck snaps and the drop pops off and springs to its spot at the
 * right edge (Delete or Stop first, then Pin, a haptic tick each). Sliding back, the card's end swallows them
 * again. Tiles are the card's height, on the same level. It is one Cohesion group drawn flat (no glass, no
 * offscreen pass), so the necks are the same smooth merge as the glass top bar's; with the glass switch on the
 * tiles also stretch under the finger. Releasing past half the reveal keeps them out; a tap on a tile calls it
 * with a function that slides the row back. TalkBack gets both as custom actions on the row.
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
    val drops = remember { SwipeDrops() }
    val frame = remember { mutableLongStateOf(0L) }
    var revealPx by remember { mutableFloatStateOf(0f) }
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

    // The drops' springs run while the card is out (or they are still settling), one step per frame.
    LaunchedEffect(drops) {
        var last = -1L
        while (true) {
            if (offset == 0f && drops.isAtRest) {
                snapshotFlow { offset }.first { it != 0f }
                last = -1L
            }
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            withFrameNanos { now ->
                val dt = if (last < 0L || scale == 0f) 0f else ((now - last) / 1e9f / scale).coerceAtMost(0.05f)
                last = now
                val pops = if (scale == 0f) drops.snap(-offset) else drops.step(dt, -offset)
                if (pops > 0) view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                frame.longValue = now
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .onSizeChanged { size ->
                with(density) {
                    drops.configure(
                        width = size.width.toFloat(),
                        tile = min(TILE_MAX_WIDTH.toPx(), size.height.toFloat()),
                        gap = TILE_GAP.toPx(),
                        breakGap = BREAK_GAP.toPx(),
                        joinGap = JOIN_GAP.toPx(),
                        squashLimit = 12.dp.toPx(),
                    )
                }
                revealPx = drops.reveal
            }
            .glassGroup(backdrop = null, group = group, style = style, mode = GlassMode.Solid, merge = TILE_MERGE),
    ) {
        if (active) {
            SwipeTile(
                index = SwipeDrops.PIN, drops = drops, frame = frame, group = group, stretchy = stretchy,
                icon = pinIcon, label = if (pinned) "Unpin" else "Pin", tint = colors.primary, enabled = isOpen,
                onClick = { onPin(close) },
            )
            SwipeTile(
                index = SwipeDrops.DELETE, drops = drops, frame = frame, group = group, stretchy = stretchy,
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
                        val limit = drops.maxSlide
                        val over = (-offset - revealPx).coerceAtLeast(0f)
                        val room = (limit - revealPx).coerceAtLeast(1f)
                        val give = if (delta < 0f && over > 0f) OVERDRAG_RESISTANCE * (1f - over / room).coerceIn(0f, 1f) else 1f
                        offset = (offset + delta * give).coerceIn(-limit, 0f)
                    },
                    onDragStarted = { settling[0]?.cancel() },
                    onDragStopped = { velocity ->
                        val open = velocity < -FLING_VELOCITY || (offset < -revealPx / 2f && velocity < FLING_VELOCITY)
                        // Kept open: any drop still held by the card pops now and springs to its spot.
                        if (open && drops.popAll() > 0) view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                        settleTo(if (open) -revealPx else 0f, if (open) settle else shut, velocity)
                    },
                )
                // Last in the chain, so switching it on and off never disturbs the drag above.
                .then(if (active) Modifier.glassPart(group, GlassShape.rounded(cardCorner)) else Modifier),
        ) { content() }
    }
}

/** One drop: a piece of the group, sized and placed from [SwipeDrops] in the layout pass (no recomposition). */
@Composable
private fun BoxScope.SwipeTile(
    index: Int,
    drops: SwipeDrops,
    frame: MutableLongState,
    group: GlassGroupState,
    stretchy: Boolean,
    @DrawableRes icon: Int,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val pull = rememberGlassPullState(TILE_PULL)
    val shape = RoundedCornerShape(ShellDimensions.CARD_CORNER)
    Box(
        Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                frame.longValue
                // The row's height comes from the card alone: when the row measures intrinsics the tile is 0 x 0.
                val rowW = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
                val rowH = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
                val squash = drops.squash(index)
                val w = (drops.tile + squash).roundToInt().coerceIn(0, rowW)
                val h = (rowH - squash * 0.6f).roundToInt().coerceIn(0, rowH)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(rowW, rowH) {
                    placeable.place((drops.center(index) - w / 2f).roundToInt(), (rowH - h) / 2)
                }
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
                    frame.longValue
                    val e = drops.exposure(index)
                    alpha = e * e
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(TILE_ICON))
        }
    }
}

/**
 * The two drops of a swipe row, as springs (plain numbers, tested on the JVM). Positions are drop centres in
 * pixels from the row's left edge; the card's right edge is `width - slide`.
 *
 * While the card holds a drop it sits in the card's end and lags behind as the card slides ([HOLD_LAG]), so it
 * bulges out and a neck forms; once it is [breakGap] clear of the card the neck snaps: it pops (a stiff hold
 * becomes a bouncy spring) and springs to its spot at the right edge. Delete pops first; Pin starts bulging
 * from the card's end after it. Sliding back, the card touching a drop ([joinGap]) takes it again and swallows
 * it as the card closes.
 */
internal class SwipeDrops {
    var width = 0f
        private set
    var tile = 0f
        private set
    private var gap = 0f
    private var breakGap = 0f
    private var joinGap = 0f
    private var squashLimit = 0f
    private val x = FloatArray(2)
    private val v = FloatArray(2)
    private val popped = BooleanArray(2)

    /** A popped drop has left the card since it popped; only then can the card take it back. */
    private val clear = BooleanArray(2)
    private var slide = 0f

    /** Slide that shows both drops at their spots, with the resting gap before the card. */
    val reveal: Float get() = 2f * (tile + gap)

    /** Slide at which Delete pops (Pin pops at twice this). */
    val firstPop: Float get() = (tile + breakGap) / HOLD_LAG

    /** How far a finger can drag the card: a little past the second pop. */
    val maxSlide: Float get() = maxOf(reveal * 1.6f, 2f * firstPop + gap)

    val isAtRest: Boolean get() = !popped[0] && !popped[1] && abs(v[0]) < REST_SPEED && abs(v[1]) < REST_SPEED

    fun configure(width: Float, tile: Float, gap: Float, breakGap: Float, joinGap: Float, squashLimit: Float) {
        val fresh = this.width == 0f
        this.width = width
        this.tile = tile
        this.gap = gap
        this.breakGap = breakGap
        this.joinGap = joinGap
        this.squashLimit = squashLimit
        if (fresh || slide == 0f) snap(0f)
    }

    fun spot(i: Int): Float = width - tile / 2f - i * (tile + gap)

    private fun edge(): Float = width - slide

    /** Where the card holds drop [i]: in its end, bulging out as the card slides on. */
    private fun held(i: Int): Float {
        val out = HOLD_LAG * (slide - i * firstPop).coerceAtLeast(0f)
        return edge() - tile / 2f + minOf(out, tile + breakGap + joinGap)
    }

    fun center(i: Int): Float = x[i]

    fun isPopped(i: Int): Boolean = popped[i]

    /** Squash and stretch along the motion as it pops and bounces (pixels added to its width). */
    fun squash(i: Int): Float = abs((v[i] * SQUASH_PER_SPEED).coerceIn(-squashLimit, squashLimit))

    /** 0 inside the card, 1 once it is fully out: the icon fades in with it. */
    fun exposure(i: Int): Float =
        if (popped[i]) 1f else (((x[i] + tile / 2f - edge()) - tile * 0.1f) / tile).coerceIn(0f, 1f)

    /** Pops every drop the card still holds; returns how many popped. */
    fun popAll(): Int {
        var n = 0
        for (i in 0..1) if (!popped[i]) { popped[i] = true; clear[i] = false; n++ }
        return n
    }

    /** Jumps straight to where the springs would settle (animations off). */
    fun snap(slide: Float): Int {
        this.slide = slide
        var n = 0
        if (slide <= 0f) { popped[0] = false; popped[1] = false }
        for (i in 0..1) {
            if (!popped[i] && slide >= (i + 1) * firstPop) { popped[i] = true; clear[i] = true; n++ }
            x[i] = if (popped[i]) spot(i) else held(i)
            v[i] = 0f
        }
        return n
    }

    /** Advances the springs by [dt] seconds with the card slid [slide] pixels; returns how many drops popped. */
    fun step(dt: Float, slide: Float): Int {
        this.slide = slide.coerceAtLeast(0f)
        if (width <= 0f || tile <= 0f) return 0
        var pops = 0
        val steps = ceil(dt / STEP).toInt().coerceAtLeast(1)
        val h = dt / steps
        repeat(steps) {
            val e = edge()
            // Pops: Delete first, Pin only after it. The neck snaps once the drop is breakGap clear of the card.
            for (i in 0..1) {
                if (popped[i] || (i == PIN && !popped[DELETE])) continue
                if ((x[i] - tile / 2f) - e > breakGap) { popped[i] = true; clear[i] = true; pops++ }
            }
            for (i in 0..1) if (popped[i] && (x[i] - tile / 2f) - e > joinGap) clear[i] = true
            // Re-joins: the card has to reach the drop (Pin first, Delete only after it).
            if (popped[PIN] && clear[PIN] && e + joinGap >= x[PIN] - tile / 2f) popped[PIN] = false
            if (popped[DELETE] && clear[DELETE] && !popped[PIN] && e + joinGap >= x[DELETE] - tile / 2f) popped[DELETE] = false
            for (i in 0..1) {
                val goal = if (popped[i]) spot(i) else held(i)
                val k = if (popped[i]) FREE_STIFFNESS else HELD_STIFFNESS
                val c = 2f * sqrt(k) * if (popped[i]) FREE_DAMPING else HELD_DAMPING
                v[i] += (k * (goal - x[i]) - c * v[i]) * h
                x[i] += v[i] * h
            }
        }
        return pops
    }

    companion object {
        const val DELETE = 0
        const val PIN = 1
        /** The drop moves this much of the card's slide out of the card's end while held. */
        const val HOLD_LAG = 0.7f
        private const val STEP = 1f / 240f
        private const val HELD_STIFFNESS = 1800f
        private const val HELD_DAMPING = 0.95f
        private const val FREE_STIFFNESS = 420f
        private const val FREE_DAMPING = 0.38f
        private const val SQUASH_PER_SPEED = 0.018f
        private const val REST_SPEED = 1f
    }
}

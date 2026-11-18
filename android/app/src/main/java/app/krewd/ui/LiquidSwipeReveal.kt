package app.krewd.ui

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

private val DROP_DIAMETER = 48.dp
private val DROP_MIN_INSET = 4.dp
private val DROP_GAP = 10.dp
private val DROP_ICON = 22.dp
private const val OVERDRAG_RESISTANCE = 0.5f
private const val OVERDRAG_LIMIT = 1.6f
private const val FLING_VELOCITY = 1500f

/**
 * Slide a row left and Pin and Delete come out of its end as liquid drops: they swell out of the card, stretch
 * on a neck and pinch off (Delete first, with a haptic tick each), the way a Meniscus nav-bar tab detaches.
 * Releasing past half the reveal keeps them out; a tap on a drop calls it with a function that slides the
 * row back, and the drops merge back into the card. TalkBack gets both as custom actions on the row.
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
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val view = LocalView.current
    val model = remember { LiquidRevealModel() }
    val renderer = remember { lazy(LazyThreadSafetyMode.NONE) { LiquidRenderer() } }
    // The card's offset. Drags write it straight away; only the settle after a release animates it, and a new
    // drag cancels that settle, so a late drag step can never cancel the spring back (which left the card stuck).
    var offset by remember { mutableFloatStateOf(0f) }
    val settling = remember { arrayOfNulls<Job>(1) }
    val frame = remember { mutableLongStateOf(0L) }
    var revealPx by remember { mutableFloatStateOf(0f) }
    var dropPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val settle = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
    val shut = spring<Float>(dampingRatio = 0.85f, stiffness = 700f)
    fun settleTo(target: Float, spec: AnimationSpec<Float>, velocity: Float = 0f) {
        settling[0]?.cancel()
        settling[0] = scope.launch { animate(offset, target, velocity, spec) { value, _ -> offset = value } }
    }
    val close: () -> Unit = { settleTo(0f, shut) }
    val isOpen by remember { derivedStateOf { offset < -revealPx / 2f } }

    LaunchedEffect(model) {
        var last = -1L
        var pinches = model.pinchCount
        while (true) {
            if (model.isAtRest) {
                snapshotFlow { offset }.first { model.width > 0f && model.width + it != model.edge }
                last = -1L
            }
            // The system animator duration scale; 0 means jump straight to the end.
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            withFrameNanos { now ->
                model.setEdge(model.width + offset)
                if (scale == 0f) model.snapToTarget() else if (last >= 0L) model.step(((now - last) / scale).toLong())
                last = now
                frame.longValue = now
            }
            if (model.pinchCount != pinches) {
                pinches = model.pinchCount
                view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .onSizeChanged { size ->
                with(density) {
                    val drop = min(DROP_DIAMETER.toPx(), size.height - 2f * DROP_MIN_INSET.toPx()).coerceAtLeast(1f)
                    model.configure(size.width.toFloat(), drop, DROP_GAP.toPx())
                    model.setEdge(size.width + offset)
                    revealPx = model.revealWidth
                    dropPx = drop.roundToInt()
                }
            },
    ) {
        Box(
            Modifier.matchParentSize().drawBehind {
                frame.longValue
                if (model.width > 0f && model.isVisible) {
                    renderer.value.draw(this, model, cardColor.toArgb(), colors.secondaryContainer.toArgb(), colors.errorContainer.toArgb())
                }
            },
        )
        LiquidDrop(
            slot = 1, model = model, frame = frame, sizePx = dropPx, icon = pinIcon,
            label = if (pinned) "Unpin" else "Pin", tint = colors.onSecondaryContainer, enabled = isOpen,
            onClick = { onPin(close) },
        )
        LiquidDrop(
            slot = 2, model = model, frame = frame, sizePx = dropPx, icon = deleteIcon,
            label = deleteLabel, tint = colors.onErrorContainer, enabled = isOpen,
            onClick = { onDelete(close) },
        )
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
                ),
        ) { content() }
    }
}

/** The tappable icon over a drop; it follows the drop's centre and fades in as the drop leaves the card. */
@Composable
private fun BoxScope.LiquidDrop(
    slot: Int,
    model: LiquidRevealModel,
    frame: MutableLongState,
    sizePx: Int,
    @DrawableRes icon: Int,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val sizeDp = with(LocalDensity.current) { sizePx.toDp() }
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .offset {
                frame.longValue
                IntOffset((model.center(slot) - sizePx / 2f).roundToInt(), 0)
            }
            .size(sizeDp)
            .graphicsLayer {
                frame.longValue
                val e = model.exposure(slot)
                alpha = e * e
                val s = 0.5f + 0.5f * e
                scaleX = s
                scaleY = s
            }
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(DROP_ICON))
    }
}

/** Owns the shader, paint and uniform buffers; a frame only rewrites uniforms. */
private class LiquidRenderer {
    private val shader = RuntimeShader(LIQUID_AGSL)
    private val paint = Paint().apply { shader = this@LiquidRenderer.shader }
    private val slots = FloatArray(LiquidRevealModel.SLOTS * 2)
    private val blends = FloatArray(LiquidRevealModel.BOUNDARIES)
    private val bridges = FloatArray(LiquidRevealModel.BOUNDARIES * 4)
    private val colors = IntArray(3)
    private var colorsSet = false

    fun draw(scope: DrawScope, model: LiquidRevealModel, card: Int, pin: Int, delete: Int) {
        if (!colorsSet || card != colors[0] || pin != colors[1] || delete != colors[2]) {
            shader.setColorUniform("uC0", card)
            shader.setColorUniform("uC1", pin)
            shader.setColorUniform("uC2", delete)
            colors[0] = card
            colors[1] = pin
            colors[2] = delete
            colorsSet = true
        }
        model.fillSlots(slots)
        model.fillBlends(blends)
        model.fillBridges(bridges)
        shader.setFloatUniform("uCy", scope.size.height * 0.5f)
        shader.setFloatUniform("uR", model.radius)
        shader.setFloatUniform("uSlots", slots)
        shader.setFloatUniform("uBlend", blends)
        shader.setFloatUniform("uBridges", bridges)
        // Only the strip right of the card can show anything.
        val from = (model.edge - 2f * model.radius).coerceAtLeast(0f)
        scope.drawContext.canvas.nativeCanvas.drawRect(from, 0f, scope.size.width, scope.size.height, paint)
    }
}

/**
 * Union of three capsules on one axis (the card's end, Pin, Delete) and the capillary bridges between them
 * (a box minus two circular fillets, see LiquidRevealModel.bridgeGeometry), with an analytic 1 px edge.
 * Colour blends from card to Pin to Delete across each junction over the neck half-width.
 */
private val LIQUID_AGSL = """
uniform float uCy;
uniform float uR;
uniform float uSlots[6];
uniform float uBlend[2];
uniform float uBridges[8];
layout(color) uniform half4 uC0;
layout(color) uniform half4 uC1;
layout(color) uniform half4 uC2;

float sdPill(float2 p, float l, float r) {
    float hx = max(0.5 * (r - l) - uR, 0.0);
    float2 q = float2(max(abs(p.x - 0.5 * (l + r)) - hx, 0.0), p.y - uCy);
    return length(q) - uR;
}

float sdBridge(float2 p, float xm, float hw, float k, float n) {
    float2 q = float2(p.x - xm, abs(p.y - uCy));
    float hh = n + k;
    float2 d = float2(abs(q.x) - hw, q.y - hh);
    float box = min(max(d.x, d.y), 0.0) + length(max(d, 0.0));
    return max(box, k - length(float2(q.x, q.y - hh)));
}

half4 main(float2 p) {
    float d = 10000.0;
    for (int i = 0; i < 3; i++) {
        d = min(d, sdPill(p, uSlots[i * 2], uSlots[i * 2 + 1]));
    }
    for (int j = 0; j < 2; j++) {
        float n = uBridges[j * 4 + 3];
        if (n > 0.0) {
            d = min(d, sdBridge(p, uBridges[j * 4], uBridges[j * 4 + 1], uBridges[j * 4 + 2], n));
        }
    }
    half4 c = mix(uC0, uC1, half(smoothstep(uBridges[0] - uBlend[0], uBridges[0] + uBlend[0], p.x)));
    c = mix(c, uC2, half(smoothstep(uBridges[4] - uBlend[1], uBridges[4] + uBlend[1], p.x)));
    return c * half(clamp(0.5 - d, 0.0, 1.0));
}
"""

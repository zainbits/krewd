package app.krewd.ui

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.krewd.LiveInfo
import app.krewd.data.EngineInfo
import app.krewd.data.SessionInfo
import kotlin.math.PI
import kotlin.random.Random
import kotlin.math.sin

/** What a crew member is doing, shown with its eyes. */
enum class FaceState(val label: String) {
    Idle("Idle"),
    Thinking("Thinking"),
    Reading("Reading"),
    Writing("Writing"),
    NeedsYou("Needs you"),
    Done("Done"),
    Error("Error"),
    Asleep("Asleep"),
}

enum class FaceBody { Round, Square, Egg }

/** One agent's look: its body shape and colour. Faces have eyes only, no mouth. */
@Immutable
data class KrewdLook(val body: FaceBody, val color: Color)

private val Coral = Color(0xFFFFB4A1)
private val Mint = Color(0xFF8FD9C0)
private val EXTRA = listOf(Color(0xFFA8C8FF), Color(0xFFF2D27A), Color(0xFFF5B0C8), Color(0xFF9EE0E8))
val FaceEyes = Color(0xFF16181D)

/** The look an engine gets: from its agent's name for the agents we know, otherwise in turn from a palette. */
fun lookFor(engineId: String?, engines: List<EngineInfo>): KrewdLook {
    val engine = engines.firstOrNull { it.id == engineId }
    val name = listOfNotNull(engineId, engine?.name, engine?.agent?.name, engine?.agent?.title).joinToString(" ").lowercase()
    return when {
        "claude" in name -> KrewdLook(FaceBody.Round, Coral)
        "codex" in name || "openai" in name -> KrewdLook(FaceBody.Square, Mint)
        else -> {
            val i = engines.indexOfFirst { it.id == engineId }.coerceAtLeast(0)
            KrewdLook(FaceBody.entries[i % FaceBody.entries.size], EXTRA[i % EXTRA.size])
        }
    }
}

/** The face a task's agent makes: needs you, the live activity while it runs, error, or done. */
fun faceOf(session: SessionInfo, live: LiveInfo?): FaceState = when {
    session.pendingApprovals > 0 || session.status == "waiting" -> FaceState.NeedsYou
    session.status == "running" -> when (live?.kind) {
        "read" -> FaceState.Reading
        "write" -> FaceState.Writing
        "needs" -> FaceState.NeedsYou
        else -> FaceState.Thinking
    }
    session.status == "error" -> FaceState.Error
    else -> FaceState.Done
}

/** An agent's face across its tasks: needs you beats working, working beats idle. */
fun faceOfEngine(engineId: String, sessions: List<SessionInfo>, live: Map<String, LiveInfo>): FaceState {
    val mine = sessions.filter { it.engine == engineId && !it.archived }
    mine.firstOrNull { it.pendingApprovals > 0 || it.status == "waiting" }?.let { return FaceState.NeedsYou }
    mine.firstOrNull { it.status == "running" }?.let { return faceOf(it, live[it.id]) }
    return FaceState.Idle
}

private class EyeSpot(val left: Float, val right: Float, val y: Float)

private fun eyesOf(body: FaceBody) = when (body) {
    FaceBody.Round -> EyeSpot(36f, 64f, 50f)
    FaceBody.Square -> EyeSpot(36f, 64f, 51f)
    FaceBody.Egg -> EyeSpot(37f, 63f, 48f)
}

private fun periodOf(state: FaceState) = when (state) {
    FaceState.Idle -> 8000
    FaceState.Thinking -> 3200
    FaceState.Reading -> 2600
    FaceState.Writing -> 450
    FaceState.NeedsYou -> 1300
    FaceState.Done -> 2800
    FaceState.Error -> 2600
    FaceState.Asleep -> 2600
}

/**
 * A crew member: a soft body and two capsule eyes that act out [state]. It is drawn in a 100-unit box scaled
 * to [size], so it stays sharp from a list row to full screen. Animated values are read while drawing only,
 * so a face redraws each frame without recomposing. [animate] off draws a still face (long lists).
 */
@Composable
fun CrewFace(
    look: KrewdLook,
    state: FaceState,
    size: Dp,
    modifier: Modifier = Modifier,
    description: String? = null,
    animate: Boolean = true,
) {
    val small = size < 28.dp
    // Each face gets its own random timing so a row of faces never moves or blinks in lockstep.
    val seed = remember { Random.nextFloat() }
    key(state, animate) {
        val transition = rememberInfiniteTransition(label = "face")
        val phase = transition.loop(if (animate) periodOf(state) else 0, "phase", seed)
        val blink = transition.blink(animate, seed)
        val breathe = transition.loop(if (animate) 3600 else 0, "breathe", (seed * 7.3f) % 1f)
        Canvas(
            modifier
                .size(size)
                .semantics { contentDescription = description ?: state.label },
        ) {
            val u = this.size.minDimension / 100f
            withTransform({ scale(u, u, Offset.Zero) }) {
                drawFace(look, state, phase.value, blink.value, breathe.value, small)
            }
        }
    }
}

@Composable
private fun InfiniteTransition.loop(period: Int, label: String, seed: Float): State<Float> =
    if (period <= 0) remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    else animateFloat(
        0f, 1f,
        infiniteRepeatable(
            tween(period, easing = LinearEasing), RepeatMode.Restart,
            initialStartOffset = StartOffset((period * seed).toInt(), StartOffsetType.FastForward),
        ),
        label = label,
    )

@Composable
private fun InfiniteTransition.blink(animate: Boolean, seed: Float): State<Float> {
    if (!animate) return remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    // Blink gap 3.4-5.4 s per face, started mid-cycle.
    val total = 3400 + (seed * 2000).toInt()
    return animateFloat(
        1f, 1f,
        infiniteRepeatable(
            keyframes { durationMillis = total; 1f at total - 360; 0.08f at total - 230; 1f at total - 100 },
            initialStartOffset = StartOffset(((seed * 13.7f) % 1f * total).toInt(), StartOffsetType.FastForward),
        ),
        label = "blink",
    )
}

/** Linear interpolation over keyframes [(at, value)], at in 0..1 and sorted. */
private fun track(p: Float, vararg keys: Pair<Float, Float>): Float {
    if (p <= keys.first().first) return keys.first().second
    for (i in 1 until keys.size) {
        val (a, va) = keys[i - 1]
        val (b, vb) = keys[i]
        if (p <= b) return va + (vb - va) * ((p - a) / (b - a).coerceAtLeast(1e-4f))
    }
    return keys.last().second
}

private fun DrawScope.drawFace(look: KrewdLook, state: FaceState, p: Float, blink: Float, breathe: Float, small: Boolean) {
    val c = Offset(50f, 52f)
    // Whole-body motion: a hop when it needs you, a shake on error, a wiggle when done.
    val hop = if (state == FaceState.NeedsYou) -9f * track(p, 0f to 0f, .25f to 1f, .55f to 0f, 1f to 0f) else 0f
    val shake = if (state == FaceState.Error) 3f * track(p, 0f to 0f, .02f to -1f, .04f to 1f, .06f to -1f, .08f to 1f, .1f to 0f, 1f to 0f) else 0f
    val tilt = if (state == FaceState.Done) track(p, 0f to 0f, .7f to 0f, .76f to -7f, .84f to 6f, .92f to -2f, 1f to 0f) else 0f
    val breathing = state == FaceState.Idle || state == FaceState.Asleep
    val b = if (breathing) sin(breathe * 2 * PI).toFloat() * 0.035f else 0f
    withTransform({
        translate(shake, hop)
        rotate(tilt, c)
        scale(1f + b, 1f - b, Offset(50f, 96f))
    }) {
        drawBody(look)
        val eyes = eyesOf(look.body)
        val (lx, ly) = lookOf(state, p)
        val grow = if (state == FaceState.NeedsYou) 1.14f else 1f
        withTransform({
            translate(lx, ly + if (state == FaceState.NeedsYou) 2f else 0f)
            scale(grow, grow, Offset(50f, eyes.y))
        }) {
            when (state) {
                FaceState.Done -> listOf(eyes.left, eyes.right).forEach { x ->
                    val arc = Path().apply { moveTo(x - 7f, eyes.y + 3f); quadraticTo(x, eyes.y - 7f, x + 7f, eyes.y + 3f) }
                    drawPath(arc, FaceEyes, style = Stroke(5f, cap = StrokeCap.Round))
                }
                FaceState.Asleep -> listOf(eyes.left, eyes.right).forEach { x ->
                    drawRoundRect(FaceEyes, Offset(x - 7f, eyes.y), Size(14f, 4.5f), CornerRadius(2.25f))
                }
                FaceState.Error -> listOf(eyes.left, eyes.right).forEach { x ->
                    drawLine(FaceEyes, Offset(x - 6f, eyes.y - 6f), Offset(x + 6f, eyes.y + 6f), 4.8f, StrokeCap.Round)
                    drawLine(FaceEyes, Offset(x + 6f, eyes.y - 6f), Offset(x - 6f, eyes.y + 6f), 4.8f, StrokeCap.Round)
                }
                else -> {
                    val open = if (state == FaceState.Thinking) 0.62f else blink
                    val h = 20f * open
                    listOf(eyes.left, eyes.right).forEach { x ->
                        drawRoundRect(FaceEyes, Offset(x - 5.5f, eyes.y - h / 2), Size(11f, h), CornerRadius(5.5f, minOf(5.5f, h / 2)))
                    }
                }
            }
        }
    }
    if (small) return
    // State marks outside the face: thought dots, a drifting "z".
    if (state == FaceState.Thinking) {
        val bob = sin(p * 2 * PI).toFloat() * 2f
        drawCircle(look.color, 3.6f, Offset(80f, 16f + bob))
        drawCircle(look.color, 2.6f, Offset(90f, 7f - bob))
    }
    if (state == FaceState.Asleep) {
        val a = track(p, 0f to 0f, .3f to 1f, 1f to 0f)
        val dx = 6f * p
        val dy = 4f - 12f * p
        val z = Path().apply { moveTo(76f + dx, 6f + dy); lineTo(86f + dx, 6f + dy); lineTo(76f + dx, 16f + dy); lineTo(86f + dx, 16f + dy) }
        drawPath(z, look.color.copy(alpha = a), style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Where the eyes look, in face units. */
private fun lookOf(state: FaceState, p: Float): Pair<Float, Float> = when (state) {
    FaceState.Idle -> track(p, 0f to 0f, .38f to 0f, .46f to -6f, .6f to -6f, .68f to 5f, .84f to 5f, .92f to 0f, 1f to 0f) to
        track(p, 0f to 0f, .46f to 1f, .6f to 1f, .68f to -1f, .84f to -1f, .92f to 0f, 1f to 0f)
    // Reading: two lines, left to right, then back.
    FaceState.Reading -> track(p, 0f to -7f, .4f to 7f, .5f to -7f, .9f to 7f, 1f to -7f) to
        track(p, 0f to -3f, .4f to -3f, .5f to 3f, .9f to 3f, 1f to -3f)
    FaceState.Writing -> (if (p < .5f) -1.5f else 1.5f) to 7f
    FaceState.Thinking -> (1.5f + 3.5f * sin(p * 2 * PI).toFloat()) to -7.5f
    else -> 0f to 0f
}

private fun DrawScope.drawBody(look: KrewdLook) {
    when (look.body) {
        FaceBody.Round -> drawCircle(look.color, 45f, Offset(50f, 53f))
        FaceBody.Square -> drawRoundRect(look.color, Offset(6f, 9f), Size(88f, 84f), CornerRadius(31f))
        FaceBody.Egg -> drawPath(
            Path().apply {
                moveTo(50f, 3f)
                cubicTo(77f, 3f, 93f, 31f, 93f, 59f)
                cubicTo(93f, 83f, 75f, 98f, 50f, 98f)
                cubicTo(25f, 98f, 7f, 83f, 7f, 59f)
                cubicTo(7f, 31f, 23f, 3f, 50f, 3f)
                close()
            },
            look.color,
        )
    }
}

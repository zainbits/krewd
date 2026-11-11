package app.crew.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.crew.CrewViewModel
import app.crew.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val PIP_MARGIN = 12.dp
private val MIN_WIDE = 110.dp
private val MIN_TALL = 150.dp
private val GRIP = 36.dp

/**
 * A live view of a browser tab floating over a chat (watch-only). Drag it anywhere: it snaps to the nearest
 * corner. Drag the grip on its inner corner to resize it: a horizontal tab by width, up to the screen's width;
 * a vertical tab by height, up to the room between the title and the composer. The tab sets the shape, and
 * the size is remembered. Tap it to open the tab full screen; the close button only hides it.
 */
@Composable
fun BrowserPip(
    tabId: String,
    label: String,
    viewModel: CrewViewModel,
    top: Dp,
    bottom: Dp,
    onClose: () -> Unit,
    onOpen: () -> Unit,
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    DisposableEffect(tabId) {
        viewModel.watchTab(tabId)
        onDispose { viewModel.unwatch(tabId) }
    }
    val current = frame?.takeIf { it.tabId == tabId }
    val ratio = current?.let { it.bitmap.width.toFloat() / it.bitmap.height } ?: (16f / 9f)
    val wide = ratio >= 1f
    var wideWidth by remember { mutableStateOf(viewModel.pipSize(wide = true).dp) }
    var tallHeight by remember { mutableStateOf(viewModel.pipSize(wide = false).dp) }
    var resizing by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = top, bottom = bottom)) {
        val maxW = (maxWidth - PIP_MARGIN * 2).coerceAtLeast(MIN_WIDE)
        val maxH = (maxHeight - PIP_MARGIN * 2).coerceAtLeast(MIN_TALL)
        val width: Dp
        val height: Dp
        if (wide) {
            width = wideWidth.coerceIn(MIN_WIDE, maxW)
            height = (width / ratio).coerceAtMost(maxH)
        } else {
            height = tallHeight.coerceIn(MIN_TALL, maxH)
            width = (height * ratio).coerceAtMost(maxW)
        }
        val maxX = with(density) { (maxWidth - width - PIP_MARGIN * 2).toPx() }.coerceAtLeast(0f)
        val maxY = with(density) { (maxHeight - height - PIP_MARGIN * 2).toPx() }.coerceAtLeast(0f)
        var corner by remember { mutableStateOf(Offset(1f, 0f)) } // top right
        val position = remember { Animatable(Offset(maxX, 0f), Offset.VectorConverter) }
        LaunchedEffect(maxX, maxY, corner) {
            val target = Offset(corner.x * maxX, corner.y * maxY)
            // While resizing, the anchored corner stays put; otherwise glide to the corner.
            if (resizing) position.snapTo(target) else position.animateTo(target, spring())
        }
        // The grip sits on the corner that faces the middle of the screen.
        val gripLeft = corner.x > 0.5f
        val gripTop = corner.y > 0.5f
        Surface(
            modifier = Modifier
                .padding(PIP_MARGIN)
                .offset { IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt()) }
                .size(width, height)
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = {
                            corner = Offset(if (position.value.x > maxX / 2) 1f else 0f, if (position.value.y > maxY / 2) 1f else 0f)
                            scope.launch { position.animateTo(Offset(corner.x * maxX, corner.y * maxY), spring()) }
                        },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { position.snapTo(position.value + drag) }
                    }
                }
                .pointerInput(Unit) { detectTapGestures { onOpen() } },
            shape = RoundedCornerShape(16.dp),
            color = Color.Black,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 8.dp,
        ) {
            Box {
                if (current == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(20.dp), strokeWidth = 2.dp)
                } else {
                    Image(
                        bitmap = current.bitmap.asImageBitmap(),
                        contentDescription = "Live view of the agent's tab",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(start = if (!gripTop && gripLeft) GRIP else 8.dp, end = 8.dp, top = 14.dp, bottom = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).background(Color(0xFFFF5A5A), CircleShape))
                    Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier
                        .align(if (gripTop && !gripLeft) Alignment.TopStart else Alignment.TopEnd)
                        .padding(5.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(0xAA000000))
                        .pointerInput(Unit) { detectTapGestures { onClose() } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Hide the live view", tint = Color.White, modifier = Modifier.size(15.dp))
                }
                Canvas(
                    Modifier
                        .align(
                            when {
                                gripTop && gripLeft -> Alignment.TopStart
                                gripTop -> Alignment.TopEnd
                                gripLeft -> Alignment.BottomStart
                                else -> Alignment.BottomEnd
                            },
                        )
                        .size(GRIP)
                        .semantics { contentDescription = "Resize the live view" }
                        .pointerInput(wide, gripLeft, gripTop, maxW, maxH) {
                            detectDragGestures(
                                onDragStart = { resizing = true },
                                onDragEnd = {
                                    resizing = false
                                    viewModel.setPipSize(wide, if (wide) wideWidth.value else tallHeight.value)
                                },
                                onDragCancel = { resizing = false },
                            ) { change, drag ->
                                change.consume()
                                if (wide) {
                                    val dx = with(density) { drag.x.toDp() }
                                    wideWidth = (width + if (gripLeft) -dx else dx).coerceIn(MIN_WIDE, maxW)
                                } else {
                                    val dy = with(density) { drag.y.toDp() }
                                    tallHeight = (height + if (gripTop) -dy else dy).coerceIn(MIN_TALL, maxH)
                                }
                            }
                        },
                ) {
                    // An L-shaped bracket pointing into the corner.
                    val inset = 9.dp.toPx()
                    val arm = 11.dp.toPx()
                    val x = if (gripLeft) inset else size.width - inset
                    val y = if (gripTop) inset else size.height - inset
                    val sx = if (gripLeft) 1f else -1f
                    val sy = if (gripTop) 1f else -1f
                    val path = Path().apply {
                        moveTo(x + sx * arm, y)
                        lineTo(x, y)
                        lineTo(x, y + sy * arm)
                    }
                    drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(path, Color.White, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
    }
}

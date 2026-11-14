package app.krewd.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.krewd.KrewdViewModel
import app.krewd.R
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val PIP_MARGIN = 12.dp
private val MIN_WIDE = 110.dp
private val MIN_TALL = 150.dp

/** Room around the PiP that also takes touches, so the second finger of a pinch can land just outside it. */
private val REACH = 16.dp

/** The PiP as last laid out. The gesture reads it fresh, because it must not restart while the PiP grows under the fingers. */
private class PipFit(val wide: Boolean, val side: Dp, val maxSide: Dp, val maxX: Float, val maxY: Float)

/**
 * A live view of a browser tab floating over a chat (watch-only). Drag it anywhere: it snaps to the nearest
 * corner. Pinch it to resize: a horizontal tab by width, up to the screen's width; a vertical tab by height, up
 * to the room between the title and the composer. The tab sets the shape, and the size is remembered. Tap it to
 * open the tab full screen; the close button only hides it.
 */
@Composable
fun BrowserPip(
    tabId: String,
    label: String,
    viewModel: KrewdViewModel,
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
    var gesturing by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = top, bottom = bottom)) {
        val roomW = maxWidth - PIP_MARGIN * 2
        val roomH = maxHeight - PIP_MARGIN * 2
        // The tab keeps its shape, so the side the pinch follows stops where the other side would overflow.
        val minSide = if (wide) MIN_WIDE else MIN_TALL
        val maxSide = (if (wide) minOf(roomW, roomH * ratio) else minOf(roomH, roomW / ratio)).coerceAtLeast(minSide)
        val side = (if (wide) wideWidth else tallHeight).coerceIn(minSide, maxSide)
        val width = if (wide) side else side * ratio
        val height = if (wide) side / ratio else side
        val maxX = with(density) { (roomW - width).toPx() }.coerceAtLeast(0f)
        val maxY = with(density) { (roomH - height).toPx() }.coerceAtLeast(0f)
        val fit by rememberUpdatedState(PipFit(wide, side, maxSide, maxX, maxY))
        var corner by remember { mutableStateOf(Offset(1f, 0f)) } // top right
        val position = remember { Animatable(Offset(maxX, 0f), Offset.VectorConverter) }
        LaunchedEffect(maxX, maxY, corner) {
            // Under the fingers the PiP goes where they take it; otherwise it rests in its corner.
            if (!gesturing) position.animateTo(Offset(corner.x * maxX, corner.y * maxY), spring())
        }
        Box(
            Modifier
                .offset {
                    val edge = (PIP_MARGIN - REACH).toPx()
                    IntOffset((position.value.x + edge).roundToInt(), (position.value.y + edge).roundToInt())
                }
                .size(width + REACH * 2, height + REACH * 2)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val reach = REACH.toPx()
                        val onPip = down.position.x in reach..(size.width - reach) && down.position.y in reach..(size.height - reach)
                        val slop = viewConfiguration.touchSlop
                        var moving = false
                        var pan = Offset.Zero
                        var zoom = 1f
                        var length = fit.side
                        var at = position.value
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) {
                                // A tap opens the tab, unless the close button took it.
                                if (!moving && onPip && event.changes.none { it.isConsumed }) onOpen()
                                break
                            }
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!moving) {
                                pan += panChange
                                zoom *= zoomChange
                                if (pan.getDistance() > slop || abs(1f - zoom) * event.calculateCentroidSize(useCurrent = false) > slop) {
                                    moving = true
                                    gesturing = true
                                    length = fit.side
                                    at = position.value
                                }
                            }
                            if (!moving) continue
                            var shift = panChange
                            if (zoomChange != 1f) {
                                val now = fit
                                val next = (length * zoomChange).coerceIn(if (now.wide) MIN_WIDE else MIN_TALL, now.maxSide)
                                // Grow around the fingers: the point between them stays under them.
                                val between = event.calculateCentroid(useCurrent = true) - Offset(reach, reach)
                                shift += between * (1f - next / length)
                                length = next
                                if (now.wide) wideWidth = next else tallHeight = next
                            }
                            at += shift
                            val target = at
                            scope.launch { position.snapTo(target) }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        if (moving) {
                            gesturing = false
                            val now = fit
                            viewModel.setPipSize(now.wide, length.value)
                            corner = Offset(if (at.x > now.maxX / 2) 1f else 0f, if (at.y > now.maxY / 2) 1f else 0f)
                            scope.launch { position.animateTo(Offset(corner.x * now.maxX, corner.y * now.maxY), spring()) }
                        }
                    }
                },
        ) {
            Surface(
                modifier = Modifier
                    .padding(REACH)
                    .fillMaxSize()
                    .semantics { onClick(label = "Open the tab") { onOpen(); true } },
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
                            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).background(Color(0xFFFF5A5A), CircleShape))
                        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xAA000000))
                            .pointerInput(Unit) { detectTapGestures { onClose() } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Hide the live view", tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

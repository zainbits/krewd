package app.krewd.ui

import io.github.zainbits.cohesion.rememberGlassBackdrop
import io.github.zainbits.cohesion.glassSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.krewd.KrewdViewModel
import app.krewd.R
import app.krewd.data.ImageRef
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Size of thumbnails in chips, bubbles and the composer tray; the viewer decodes [FULL_SIDE]. */
const val THUMB_SIDE = 640
private const val FULL_SIDE = 2880
private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Loads pictures for the screen and opens the full-screen viewer; provided once at the app's root. */
class ImageViewerController(
    val load: suspend (ImageRef, Int) -> ImageBitmap?,
    val open: (List<ImageRef>, Int) -> Unit,
)

val LocalImageViewer = staticCompositionLocalOf<ImageViewerController?> { null }

/** Provides [LocalImageViewer] to [content] and shows the viewer above everything when a picture is opened. */
@Composable
fun ImageViewerHost(viewModel: KrewdViewModel, content: @Composable () -> Unit) {
    var gallery by remember { mutableStateOf<Pair<List<ImageRef>, Int>?>(null) }
    val controller = remember(viewModel) {
        ImageViewerController(
            load = { ref, side -> viewModel.image(ref, side) },
            open = { list, index -> if (list.isNotEmpty()) gallery = list to index.coerceIn(0, list.lastIndex) },
        )
    }
    CompositionLocalProvider(LocalImageViewer provides controller) {
        content()
        gallery?.let { (list, start) ->
            ImageViewer(list, start, controller, onSave = viewModel::saveImage, onShare = viewModel::shareImage, onDismiss = { gallery = null })
        }
    }
}

/** A picture loaded through [LocalImageViewer]: a spinner while it loads, a short message if it cannot. */
@Composable
fun rememberImage(ref: ImageRef, side: Int = THUMB_SIDE): ImageLoad {
    val controller = LocalImageViewer.current
    val load by produceState<ImageLoad>(ImageLoad.Loading, ref, side) {
        value = try {
            controller?.load?.invoke(ref, side)?.let { ImageLoad.Ready(it) } ?: ImageLoad.Failed("Not connected to Krewd")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ImageLoad.Failed(e.message ?: "Could not load the picture")
        }
    }
    return load
}

sealed class ImageLoad {
    data object Loading : ImageLoad()
    data class Ready(val bitmap: ImageBitmap) : ImageLoad()
    data class Failed(val reason: String) : ImageLoad()
}

/**
 * Full-screen pictures on black: swipe sideways between them, pinch or double-tap to zoom, drag to look
 * around a zoomed one, swipe down (or Back) to close, tap to hide the bars. Save keeps the original in
 * Pictures/Krewd; Share hands it to another app.
 */
@Composable
private fun ImageViewer(
    images: List<ImageRef>,
    start: Int,
    controller: ImageViewerController,
    onSave: (ImageRef) -> Unit,
    onShare: (ImageRef) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pager = rememberPagerState(initialPage = start) { images.size }
        var chrome by remember { mutableStateOf(true) }
        var zoomed by remember { mutableStateOf(false) }
        var drag by remember { mutableFloatStateOf(0f) }
        val dismissAt = with(LocalDensity.current) { 140.dp.toPx() }
        // The bar's buttons are glass over the picture (a still image, so the glass redraws only on a swipe or zoom).
        val glassOn = LocalKrewdGlass.current.on
        val backdrop = rememberGlassBackdrop()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = (1f - abs(drag) / (dismissAt * 3f)).coerceIn(0.35f, 1f))),
        ) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                key = { images[it].key },
                modifier = Modifier.fillMaxSize().then(if (glassOn) Modifier.glassSource(backdrop) else Modifier),
            ) { page ->
                ZoomablePage(
                    ref = images[page],
                    current = pager.settledPage == page,
                    onTap = { chrome = !chrome },
                    onZoomed = { if (pager.settledPage == page) zoomed = it },
                    onDrag = { drag = it },
                    dismissAt = dismissAt,
                    onDismiss = onDismiss,
                )
            }
            AnimatedVisibility(chrome && drag == 0f, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                val ref = images[pager.currentPage.coerceIn(0, images.lastIndex)]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalKrewdBackdrop provides if (glassOn) backdrop else null) {
                        BarButton(glassOn, R.drawable.ic_close_24, "Close", onDismiss)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(ref.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (images.size > 1) {
                            Text("${pager.currentPage + 1} of ${images.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    CompositionLocalProvider(LocalKrewdBackdrop provides if (glassOn) backdrop else null) {
                        // Share and Save side by side: one liquid pair.
                        LiquidRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BarButton(glassOn, R.drawable.ic_share_24, "Share") { onShare(ref) }
                            BarButton(glassOn, R.drawable.ic_download_24, "Save to the phone") { onSave(ref) }
                        }
                    }
                }
            }
            if (images.size > 1) {
                AnimatedVisibility(chrome && drag == 0f, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                    Row(
                        Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        repeat(images.size.coerceAtMost(12)) { i ->
                            Box(
                                Modifier
                                    .size(if (i == pager.currentPage) 8.dp else 6.dp)
                                    .background(Color.White.copy(alpha = if (i == pager.currentPage) 0.95f else 0.45f), androidx.compose.foundation.shape.CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A top-bar button: glass over the picture, or the plain white icon while the glass is off. */
@Composable
private fun BarButton(glass: Boolean, @androidx.annotation.DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    if (glass) {
        KrewdIconPill(onClick = onClick, icon = icon, contentDescription = label, size = 44.dp, modifier = Modifier.padding(horizontal = 2.dp))
    } else {
        IconButton(onClick = onClick) { Icon(painterResource(icon), contentDescription = label, tint = Color.White) }
    }
}

/** One picture: shows the thumbnail at once if it is already loaded, then the sharper copy. */
@Composable
private fun ZoomablePage(
    ref: ImageRef,
    current: Boolean,
    onTap: () -> Unit,
    onZoomed: (Boolean) -> Unit,
    onDrag: (Float) -> Unit,
    dismissAt: Float,
    onDismiss: () -> Unit,
) {
    val controller = LocalImageViewer.current
    val load by produceState<ImageLoad>(ImageLoad.Loading, ref) {
        try {
            controller?.load?.invoke(ref, THUMB_SIDE)?.let { value = ImageLoad.Ready(it) }
            value = controller?.load?.invoke(ref, FULL_SIDE)?.let { ImageLoad.Ready(it) }
                ?: (value as? ImageLoad.Ready ?: ImageLoad.Failed("Not connected to Krewd"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (value !is ImageLoad.Ready) value = ImageLoad.Failed(e.message ?: "Could not load the picture")
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val slop = LocalViewConfiguration.current.touchSlop

    // Leaving a page zooms it back out, so it is whole when swiped back to.
    LaunchedEffect(current) {
        if (!current) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val box = androidx.compose.ui.geometry.Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        when (val l = load) {
            is ImageLoad.Ready -> {
                val bitmap = l.bitmap
                // The picture's size on screen at zoom 1 (fitted), which bounds how far a zoomed one can move.
                val fit = minOf(box.width / bitmap.width, box.height / bitmap.height)
                val shown = androidx.compose.ui.geometry.Size(bitmap.width * fit, bitmap.height * fit)
                fun clamp(o: Offset, s: Float): Offset {
                    val maxX = ((shown.width * s - box.width) / 2f).coerceAtLeast(0f)
                    val maxY = ((shown.height * s - box.height) / 2f).coerceAtLeast(0f)
                    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                }
                fun zoomTo(target: Float, focus: Offset) {
                    val from = scale
                    val fromOffset = offset
                    val center = Offset(box.width / 2f, box.height / 2f)
                    val endOffset = if (target <= 1f) Offset.Zero else clamp((center - focus) * (target - 1f), target)
                    scope.launch {
                        animate(0f, 1f) { t, _ ->
                            scale = from + (target - from) * t
                            offset = fromOffset + (endOffset - fromOffset) * t
                        }
                        onZoomed(scale > 1.01f)
                    }
                }
                Image(
                    bitmap,
                    contentDescription = ref.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(ref) {
                            detectTapGestures(
                                onTap = { onTap() },
                                onDoubleTap = { p -> zoomTo(if (scale > 1.01f) 1f else DOUBLE_TAP_ZOOM, p) },
                            )
                        }
                        .pointerInput(ref, box) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var total = Offset.Zero
                                var dismissing = false
                                var decided = false
                                do {
                                    val event = awaitPointerEvent()
                                    val fingers = event.changes.count { it.pressed }
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    // The event that lifts the last finger has no fingers down and no centre (NaN), so only
                                    // events with a finger still down move the picture.
                                    val centroid = if (fingers > 0) event.calculateCentroid(useCurrent = true) else Offset.Unspecified
                                    if (!dismissing && centroid.isSpecified && (fingers > 1 || scale > 1.01f)) {
                                        // Pinch around the fingers, and move a zoomed picture.
                                        val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                        val c = centroid - Offset(box.width / 2f, box.height / 2f)
                                        val moved = c - (c - offset) * (next / scale) + pan
                                        if (next.isFinite() && moved.x.isFinite() && moved.y.isFinite()) {
                                            scale = next
                                            offset = clamp(moved, next)
                                        }
                                        onZoomed(scale > 1.01f)
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } else if (fingers == 1) {
                                        // At zoom 1 a sideways swipe belongs to the pager; a downward one closes.
                                        if (!decided) {
                                            total += pan
                                            if (total.getDistance() > slop) {
                                                decided = true
                                                dismissing = abs(total.y) > abs(total.x) * 1.4f
                                            }
                                        }
                                        if (dismissing) {
                                            dragY += pan.y
                                            onDrag(dragY)
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                                if (dismissing) {
                                    if (abs(dragY) > dismissAt) {
                                        onDismiss()
                                    } else {
                                        val from = dragY
                                        scope.launch {
                                            animate(from, 0f) { v, _ ->
                                                dragY = v
                                                onDrag(v)
                                            }
                                        }
                                    }
                                }
                                if (scale < 1.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                    onZoomed(false)
                                }
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y + dragY
                        },
                )
            }
            is ImageLoad.Loading -> CircularProgressIndicator(color = Color.White)
            is ImageLoad.Failed -> Text(l.reason, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

package app.krewd.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.krewd.R
import app.krewd.data.FilePreview
import app.krewd.data.PreviewKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * A Markdown, PDF or HTML file shown inside the app, like an artifact: full screen over the chat, with a back arrow
 * (and Back) to the chat, and Download and Share as the secondary actions. Markdown is rendered with the chat's own
 * renderer; a PDF scrolls page by page and zooms with a pinch (double-tap resets); HTML is sandboxed in a WebView with
 * scripts and network loads off until the owner turns them on for this page.
 */
@Composable
fun FilePreviewScreen(preview: FilePreview, onBack: () -> Unit, onDownload: () -> Unit, onShare: () -> Unit) {
    BackHandler(onBack = onBack)
    val colors = MaterialTheme.colorScheme
    // Scripts turned on for a file stay on for it until the app closes, so reopening it works the same.
    var scripts by remember(preview.file) { mutableStateOf(preview.file.path in scriptsOn) }
    var network by remember(preview.file) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = colors.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(colors.surfaceContainer).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_chevron_left_24), contentDescription = "Back to the chat")
                }
                Column(Modifier.weight(1f)) {
                    Text(preview.file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text(
                        when (preview.kind) {
                            PreviewKind.Markdown -> "Markdown"
                            PreviewKind.Pdf -> "PDF"
                            PreviewKind.Html -> "HTML · " + when {
                                scripts && network -> "scripts and network on"
                                scripts -> "scripts on, network off"
                                network -> "network on, scripts off"
                                else -> "sandboxed"
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDownload) { Icon(painterResource(R.drawable.ic_download_24), contentDescription = "Download to Downloads/Krewd") }
                IconButton(onClick = onShare, enabled = preview.local != null) { Icon(painterResource(R.drawable.ic_share_24), contentDescription = "Share") }
                if (preview.kind == PreviewKind.Html) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_24), contentDescription = "Page settings") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Run scripts") },
                                trailingIcon = { Switch(checked = scripts, onCheckedChange = null) },
                                onClick = { scripts = !scripts; if (scripts) scriptsOn += preview.file.path else scriptsOn -= preview.file.path },
                            )
                            DropdownMenuItem(
                                text = { Text("Load from the network") },
                                trailingIcon = { Switch(checked = network, onCheckedChange = null) },
                                onClick = { network = !network },
                            )
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val local = preview.local
                when {
                    preview.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(preview.error, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        TextButton(onClick = onDownload) { Text("Download instead") }
                    }
                    local == null -> CircularProgressIndicator()
                    else -> when (preview.kind) {
                        PreviewKind.Markdown -> MarkdownFile(local)
                        PreviewKind.Pdf -> PdfFile(local)
                        PreviewKind.Html -> HtmlFile(local, scripts, network, onRunScripts = { scripts = true; scriptsOn += preview.file.path })
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownFile(file: File) {
    val text by produceState<String?>(null, file) { value = withContext(Dispatchers.IO) { file.readText() } }
    val body = text ?: return CircularProgressIndicator()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp).navigationBarsPadding(),
    ) {
        SelectionContainer { Markdown(body, notes = false) }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The PDF's pages, rendered one at a time as they scroll in (PdfRenderer allows one open page) at the screen's width
 * times the zoom, so text stays sharp when zoomed. Pinch to zoom (1x to 5x) and drag to look around a zoomed page.
 */
@Composable
private fun PdfFile(file: File) {
    val doc by produceState<PdfLoad>(PdfLoad.Loading, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { PdfLoad.Ready(PdfDoc(file)) }
                .getOrElse { Log.w("Krewd", "PDF preview could not open ${file.name}", it); PdfLoad.Failed }
        }
    }
    // Close the document this effect was started for. (Reading `doc` inside onDispose would read the newest value:
    // when the loaded document replaced Loading, it closed the document just opened, and the next page crashed.)
    DisposableEffect(doc) {
        val opened = (doc as? PdfLoad.Ready)?.doc
        onDispose { opened?.close() }
    }
    val pdf = when (val d = doc) {
        PdfLoad.Loading -> return CircularProgressIndicator()
        PdfLoad.Failed -> return Text("Could not read this PDF", color = MaterialTheme.colorScheme.onSurfaceVariant)
        is PdfLoad.Ready -> d.doc
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val list = rememberLazyListState()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .pointerInput(Unit) {
                pinchAndSideways { zoom, panX ->
                    val next = (scale * zoom).coerceIn(1f, 5f)
                    scale = next
                    val maxX = size.width * (next - 1f) / 2f
                    offset = if (next == 1f) Offset.Zero else Offset((offset.x + panX).coerceIn(-maxX, maxX), 0f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
            },
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        // Sharp at the zoom it settles on, rendered in steps so a pinch does not re-render every frame.
        val renderScale = when {
            scale > 3f -> 4f
            scale > 1.6f -> 2.5f
            else -> 1.5f
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
            },
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(pdf.count) { index ->
                val ratio = pdf.ratio(index)
                val page by produceState<Bitmap?>(null, index, renderScale) {
                    value = pdf.render(index, (widthPx * renderScale).toInt().coerceAtMost(4096))
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).aspectRatio(ratio).background(Color.White)) {
                    page?.let { Image(it.asImageBitmap(), contentDescription = "Page ${index + 1} of ${pdf.count}", modifier = Modifier.fillMaxSize()) }
                }
            }
        }
    }
}

/**
 * Two fingers pinch (and pan); one finger is left to the list's own vertical scrolling, and only its sideways part
 * moves a zoomed page. Watches in the Initial pass, before the list takes the move.
 */
private suspend fun PointerInputScope.pinchAndSideways(onChange: (zoom: Float, panX: Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.filter { it.pressed }
            if (down.size >= 2) {
                onChange(event.calculateZoom(), event.calculatePan().x)
                event.changes.forEach { it.consume() }
            } else if (down.size == 1) {
                val c = down[0]
                onChange(1f, c.position.x - c.previousPosition.x)
            }
        } while (event.changes.any { it.pressed })
    }
}

private sealed interface PdfLoad {
    data object Loading : PdfLoad
    data object Failed : PdfLoad
    class Ready(val doc: PdfDoc) : PdfLoad
}

/**
 * An open PDF. Opened off the main thread, where every page's shape is read once, so the list never touches the
 * renderer while composing; pages render one at a time (PdfRenderer allows one open page) and never after [close].
 */
private class PdfDoc(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(fd)
    } catch (e: Exception) {
        fd.close()
        throw e
    }
    private val lock = Mutex()
    private var closed = false
    val count: Int = renderer.pageCount
    private val ratios = FloatArray(count) { index ->
        runCatching { renderer.openPage(index).use { it.width.toFloat() / it.height.coerceAtLeast(1) } }
            .getOrDefault(A4_RATIO).coerceIn(0.2f, 5f)
    }

    fun ratio(index: Int): Float = ratios.getOrElse(index) { A4_RATIO }

    suspend fun render(index: Int, width: Int): Bitmap? = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                synchronized(renderer) {
                    if (closed) return@runCatching null
                    renderer.openPage(index).use { page ->
                        // Kept under the size Android will draw (about 100 MB): a long page gets fewer pixels.
                        val aspect = page.height.toFloat() / page.width.coerceAtLeast(1)
                        val w = minOf(width.toFloat(), sqrt(MAX_PAGE_PIXELS / aspect.coerceAtLeast(0.01f))).toInt().coerceAtLeast(1)
                        val h = (w * aspect).toInt().coerceIn(1, MAX_PAGE_SIDE)
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }.onFailure { Log.w("Krewd", "PDF page ${index + 1} did not render", it) }.getOrNull()
        }
    }

    fun close() {
        synchronized(renderer) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
        }
        runCatching { fd.close() }
    }

    private companion object {
        const val A4_RATIO = 210f / 297f
        const val MAX_PAGE_PIXELS = 16_000_000f
        const val MAX_PAGE_SIDE = 16_384
    }
}

/**
 * The HTML file in a sandboxed WebView: no file or content access, scripts and network loads off unless turned on for
 * this page (the menu); links open in the phone's browser instead of inside the preview.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlFile(file: File, scripts: Boolean, network: Boolean, onRunScripts: () -> Unit) {
    val context = LocalContext.current
    val html by produceState<String?>(null, file) { value = withContext(Dispatchers.IO) { file.readText() } }
    val page = html ?: return CircularProgressIndicator()
    val hasScripts = remember(page) { hasScripts(page) }
    Column(Modifier.fillMaxSize()) {
        // A page with scripts does nothing while they are off (buttons, menus, a calculator): say so, one tap to run.
        if (hasScripts && !scripts) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "This page has scripts. They are off, so its buttons will not work.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRunScripts) { Text("Run scripts") }
            }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.setGeolocationEnabled(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            if (url.scheme == "http" || url.scheme == "https" || url.scheme == "mailto") {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                            return true
                        }

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            // Belt and braces for blockNetworkLoads: nothing outside the page itself loads while network is off.
                            val scheme = request.url.scheme
                            if (!view.tag.toString().toBoolean() && scheme != "data" && scheme != "about") return WebResourceResponse("text/plain", "utf-8", null)
                            return null
                        }
                    }
                }
            },
            update = { view ->
                val key = "$scripts|$network|${file.path}|${file.lastModified()}"
                if (view.getTag(R.id.preview_key) == key) return@AndroidView
                view.setTag(R.id.preview_key, key)
                view.tag = network.toString()
                view.settings.javaScriptEnabled = scripts
                view.settings.blockNetworkLoads = !network
                view.loadDataWithBaseURL("about:blank", page, "text/html", "utf-8", null)
            },
            onRelease = { it.destroy() },
        )
    }
}

/** Files whose scripts the owner turned on, by path, for as long as the app runs. */
private val scriptsOn = mutableSetOf<String>()

/** Whether an HTML page has scripts: a script element, an inline event handler or a javascript: link. */
internal fun hasScripts(html: String): Boolean =
    Regex("""<script\b|\son[a-z]+\s*=|javascript:""", RegexOption.IGNORE_CASE).containsMatchIn(html)

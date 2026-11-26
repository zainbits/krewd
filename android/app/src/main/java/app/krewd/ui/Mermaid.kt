/**
 * Mermaid diagrams in agent replies. A ```mermaid code block whose fence is closed is drawn as a picture: one hidden
 * WebView loads the bundled Mermaid (assets/mermaid, no network) and turns the diagram into a PNG in the app's
 * colors, kept in the cache folder so a chat opened again shows it at once. In the chat the picture fits the
 * width; a wide one stays readable and scrolls sideways. A tap opens it in the full-screen viewer (pinch to zoom,
 * drag around, Save, Share). Code shows the source; a diagram Mermaid cannot read shows its error and the source.
 */
package app.krewd.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.krewd.data.ImageRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The smallest a diagram is shrunk to fit the chat's width; a wider one scrolls sideways instead. */
private const val MIN_FIT = 0.6f

/** Pixels of the picture per CSS pixel of the diagram: sharp on the phone, and when zoomed in the viewer. */
private const val RENDER_SCALE = 3f

/** The longest side the chat decodes a diagram at (a huge one at half its pixels); the viewer decodes its own. */
private const val DIAGRAM_SIDE = 2048

/** The colors a diagram is drawn in, from the app's theme; part of its cache key. */
data class MermaidTheme(val dark: Boolean, val background: Color, val vars: Map<String, Color>) {
    val json: String by lazy {
        JSONObject()
            .put("dark", dark)
            .put("font", "Roboto, sans-serif")
            .put("scale", RENDER_SCALE.toDouble())
            .put("background", hex(background))
            .put("vars", JSONObject().apply { vars.forEach { (k, v) -> put(k, hex(v)) }; put("fontSize", "16px") })
            .toString()
    }

    private fun hex(c: Color) = String.format("#%06x", c.toArgb() and 0xffffff)
}

/** A drawn diagram: its PNG in the cache folder and its own size in CSS pixels (drawn as that many dp). */
class Diagram(val file: File, val width: Int, val height: Int)

class MermaidError(message: String) : Exception(message)

/**
 * Draws diagrams in one hidden WebView, one at a time, made when the first diagram needs it and dropped after a
 * minute without work. Finished pictures are files named by a hash of the renderer, colors and source, so each
 * diagram is drawn once; the newest [KEEP] stay.
 */
object MermaidRenderer {
    private const val PAGE = "file:///android_asset/mermaid/index.html"
    /** Changes with the bundled Mermaid or the page, so diagrams are drawn again with the new one. */
    private const val RENDERER = "mermaid-12.3.0/1"
    private const val KEEP = 200
    private const val IDLE_MS = 60_000L
    private const val LOAD_TIMEOUT_MS = 20_000L
    private const val DRAW_TIMEOUT_MS = 20_000L

    private class Result(val png: String?, val width: Int, val height: Int, val error: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val drawing = Mutex()
    // Touched on the main thread only.
    private val inFlight = HashMap<String, Deferred<Diagram>>()
    private var web: WebView? = null
    private var loaded: CompletableDeferred<Unit>? = null
    private var idle: Job? = null
    // Answers from the page, by request id; the bridge completes them on its own thread.
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Result>>()

    /**
     * The picture for [code] in [theme], drawn now or taken from the cache. Throws [MermaidError] with Mermaid's
     * message when it cannot draw it. Leaving the screen does not stop a drawing that started, so a diagram
     * scrolled past is ready when it comes back.
     */
    suspend fun render(context: Context, code: String, theme: MermaidTheme): Diagram {
        val app = context.applicationContext
        val key = sha256("$RENDERER\n${theme.json}\n$code")
        val dir = File(app.cacheDir, "diagrams")
        withContext(Dispatchers.IO) { cached(dir, key) }?.let { return it }
        val job = withContext(Dispatchers.Main.immediate) {
            inFlight.getOrPut(key) {
                scope.async {
                    try {
                        drawing.withLock {
                            withContext(Dispatchers.IO) { cached(dir, key) }
                                ?: draw(app, code, theme).let { withContext(Dispatchers.IO) { save(dir, key, it) } }
                        }
                    } finally {
                        inFlight.remove(key)
                    }
                }
            }
        }
        return job.await()
    }

    private fun cached(dir: File, key: String): Diagram? {
        val file = dir.listFiles { f -> f.name.startsWith("$key-") && f.name.endsWith(".png") }?.firstOrNull() ?: return null
        val (w, h) = file.name.removePrefix("$key-").removeSuffix(".png").split('x').map { it.toIntOrNull() ?: return null }
        file.setLastModified(System.currentTimeMillis()) // recently shown ones stay longest
        return Diagram(file, w, h)
    }

    private fun save(dir: File, key: String, result: Result): Diagram {
        val bytes = Base64.decode(result.png ?: throw MermaidError("Mermaid drew nothing"), Base64.DEFAULT)
        dir.mkdirs()
        val file = File(dir, "$key-${result.width}x${result.height}.png")
        val part = File(dir, "$key.part")
        part.writeBytes(bytes)
        if (!part.renameTo(file)) throw MermaidError("Could not keep the diagram")
        dir.listFiles { f -> f.name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
        return Diagram(file, result.width, result.height)
    }

    /** Runs on the main thread, inside [drawing]. */
    private suspend fun draw(app: Context, code: String, theme: MermaidTheme): Result {
        idle?.cancel()
        try {
            val view = web ?: create(app)
            try {
                withTimeout(LOAD_TIMEOUT_MS) { loaded?.await() }
            } catch (e: TimeoutCancellationException) {
                destroy()
                throw MermaidError("The diagram renderer did not start")
            }
            val id = UUID.randomUUID().toString()
            val reply = CompletableDeferred<Result>()
            waiting[id] = reply
            try {
                view.evaluateJavascript("krewdRender(${JSONObject.quote(id)}, ${JSONObject.quote(code)}, ${theme.json})", null)
                val result = try {
                    withTimeout(DRAW_TIMEOUT_MS) { reply.await() }
                } catch (e: TimeoutCancellationException) {
                    destroy() // a page stuck on one diagram would hold up every other
                    throw MermaidError("The diagram took too long to draw")
                }
                result.error?.let { throw MermaidError(it.trim().ifEmpty { "Mermaid could not read the diagram" }) }
                return result
            } finally {
                waiting.remove(id)
            }
        } finally {
            idle = scope.launch {
                delay(IDLE_MS)
                drawing.withLock { destroy() }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(app: Context): WebView {
        val ready = CompletableDeferred<Unit>()
        val view = WebView(app)
        view.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true // the page and Mermaid come from the app; diagrams never fetch anything
            allowContentAccess = false
            allowFileAccess = false // still reads its own assets
        }
        view.addJavascriptInterface(Bridge(), "KrewdMermaid")
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                ready.complete(Unit)
            }

            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) = true

            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Without this the whole app would go down with the page; the next diagram makes a new one.
                if (web === v) {
                    web = null
                    loaded = null
                }
                waiting.values.forEach { it.complete(Result(null, 0, 0, "The diagram renderer stopped")) }
                v.destroy()
                return true
            }
        }
        // A size for the page to lay out in; the view is never shown.
        view.layout(0, 0, 900, 1600)
        view.loadUrl(PAGE)
        web = view
        loaded = ready
        return view
    }

    private fun destroy() {
        web?.destroy()
        web = null
        loaded = null
    }

    private class Bridge {
        @JavascriptInterface
        fun done(id: String, png: String?, width: Int, height: Int, error: String?) {
            waiting[id]?.complete(Result(png, width, height, error))
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}

/** The diagram colors from the app's theme: containers for shapes, the block's own background behind them. */
@Composable
private fun rememberMermaidTheme(): MermaidTheme {
    val c = MaterialTheme.colorScheme
    return remember(c) {
        MermaidTheme(
            dark = c.surface.luminance() < 0.5f,
            background = c.surfaceContainerLow,
            vars = mapOf(
                "background" to c.surfaceContainerLow,
                "primaryColor" to c.primaryContainer,
                "primaryTextColor" to c.onPrimaryContainer,
                "primaryBorderColor" to c.primary,
                "secondaryColor" to c.secondaryContainer,
                "secondaryTextColor" to c.onSecondaryContainer,
                "secondaryBorderColor" to c.secondary,
                "tertiaryColor" to c.tertiaryContainer,
                "tertiaryTextColor" to c.onTertiaryContainer,
                "tertiaryBorderColor" to c.tertiary,
                "lineColor" to c.onSurfaceVariant,
                "textColor" to c.onSurface,
                "titleColor" to c.onSurface,
                "noteBkgColor" to c.surfaceContainerHighest,
                "noteTextColor" to c.onSurface,
                "noteBorderColor" to c.outline,
                "edgeLabelBackground" to c.surfaceContainerLow,
                "clusterBkg" to c.surfaceContainer,
                "clusterBorder" to c.outlineVariant,
            ),
        )
    }
}

private sealed class DiagramState {
    data object Drawing : DiagramState()
    class Ready(val diagram: Diagram) : DiagramState()
    class Failed(val reason: String) : DiagramState()
}

/** A ```mermaid block: the diagram, with Code to see the source, Copy, and a tap to open it full screen. */
@Composable
internal fun MermaidBlock(code: String) {
    val context = LocalContext.current
    val theme = rememberMermaidTheme()
    val state by produceState<DiagramState>(DiagramState.Drawing, code, theme) {
        value = try {
            DiagramState.Ready(MermaidRenderer.render(context, code, theme))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagramState.Failed(e.message ?: "Could not draw the diagram")
        }
    }
    var showCode by remember { mutableStateOf(false) }
    var copies by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(copies) {
        if (copies > 0) { copied = true; delay(1_500); copied = false }
    }
    val colors = MaterialTheme.colorScheme
    val failed = state as? DiagramState.Failed
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            DisableSelection {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "mermaid",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (failed == null) BarButton(if (showCode) "Diagram" else "Code", active = false) { showCode = !showCode }
                    BarButton(if (copied) "Copied" else "Copy", active = copied) {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("mermaid", code)))
                            copies++
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            if (failed != null) {
                Text(
                    // Mermaid's parse errors go on to list every token it expected; the first line says enough.
                    text = "Could not draw the diagram: " + failed.reason.lineSequence().first().trim().removeSuffix(":"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
                )
            }
            when (val s = state) {
                is DiagramState.Ready -> if (showCode) MermaidSource(code) else DiagramPicture(s.diagram)
                DiagramState.Drawing -> if (showCode) MermaidSource(code) else Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                is DiagramState.Failed -> MermaidSource(code)
            }
        }
    }
}

@Composable
private fun MermaidSource(code: String) {
    val scroll = rememberScrollState()
    RegisterNoteScroll(scroll)
    Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        SelectionContainer {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/** The picture at the chat's width, or at [MIN_FIT] scrolling sideways when that would make it too small. */
@Composable
private fun DiagramPicture(diagram: Diagram) {
    val viewer = LocalImageViewer.current
    val ref = remember(diagram.file) { ImageRef.Diagram(diagram.file.path) }
    val load = rememberImage(ref, DIAGRAM_SIDE)
    val scroll = rememberScrollState()
    RegisterNoteScroll(scroll)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        val room = maxWidth - 20.dp
        val natural = diagram.width.dp
        val scale = (room / natural).coerceAtMost(1f).coerceAtLeast(MIN_FIT)
        val w = natural * scale
        val h = diagram.height.dp * scale
        Box(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 10.dp)) {
            Box(Modifier.widthIn(min = room), contentAlignment = Alignment.Center) {
                when (val l = load) {
                    is ImageLoad.Ready -> Image(
                        l.bitmap,
                        contentDescription = "Diagram",
                        modifier = Modifier.size(w, h).clip(RoundedCornerShape(8.dp)).clickable { viewer?.open?.invoke(listOf(ref), 0) },
                    )
                    is ImageLoad.Failed -> Text(l.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    ImageLoad.Loading -> Box(Modifier.size(w, h))
                }
            }
        }
    }
}

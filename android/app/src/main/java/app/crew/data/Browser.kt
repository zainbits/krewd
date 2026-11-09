package app.crew.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import org.json.JSONObject

/** A tab of the agents' browser. [sessionId] is the task whose agent last used it, if any. */
data class BrowserTab(val id: String, val title: String, val url: String, val sessionId: String? = null) {
    val host: String get() = url.substringAfter("://").substringBefore('/').removePrefix("www.")
}

data class BrowserTabs(val available: Boolean, val reason: String?, val tabs: List<BrowserTab>)

/**
 * One frame of the agents' browser: the picture, the page viewport it shows (CSS pixels), whether the
 * phone-sized view is on, and the page's scroll position and height (for the scroll bar).
 */
data class BrowserFrame(
    val tabId: String,
    val bitmap: Bitmap,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val vertical: Boolean = false,
    val scrollY: Float = 0f,
    val contentHeight: Float = 0f,
)

fun CrewApi.browserTabs(): BrowserTabs {
    val o = JSONObject(rawCall("GET", "/api/browser/tabs"))
    return BrowserTabs(
        available = o.optBoolean("available"),
        reason = o.optStringOrNull("reason"),
        tabs = o.optJSONArray("tabs").objects().map {
            BrowserTab(it.optString("id"), it.optString("title"), it.optString("url"), it.optStringOrNull("session_id"))
        },
    )
}

/**
 * Sends mouse, scroll, key, typed text or navigation input to the tab. Typed text is not kept anywhere.
 * After a click the answer says whether a text field now has focus ({editable, password}).
 */
fun CrewApi.browserInput(tabId: String, event: JSONObject): JSONObject =
    JSONObject(rawCall("POST", "/api/browser/tabs/$tabId/input", event))

fun CrewApi.newTab(url: String?): BrowserTab {
    val o = JSONObject(rawCall("POST", "/api/browser/tabs", JSONObject().apply { if (url != null) put("url", url) }))
    return BrowserTab(o.optString("id"), o.optString("title"), o.optString("url"))
}

fun CrewApi.closeBrowserTab(tabId: String) {
    rawCall("DELETE", "/api/browser/tabs/$tabId")
}

/** Vertical: lay the page out for the phone ([width] x [height] CSS pixels at [scale]); horizontal: the normal window. */
fun CrewApi.setTabView(tabId: String, vertical: Boolean, width: Int = 0, height: Int = 0, scale: Float = 0f) {
    val body = JSONObject().put("mode", if (vertical) "vertical" else "horizontal")
    if (vertical) body.put("width", width).put("height", height).put("scale", scale.toDouble())
    rawCall("POST", "/api/browser/tabs/$tabId/view", body)
}

/**
 * Reads the server's multipart JPEG stream for one tab and calls [onFrame] for each frame until the
 * stream ends or [connection] is disconnected from another thread.
 */
class FrameStream(val connection: HttpURLConnection, val tabId: String) {
    fun read(onFrame: (BrowserFrame) -> Unit) {
        val code = connection.responseCode
        if (code !in 200..299) throw ApiException(code, if (code == 404) "That tab is closed" else "HTTP $code")
        BufferedInputStream(connection.inputStream, 64 * 1024).use { input ->
            while (true) {
                var line = input.readLine() ?: return
                while (line.isBlank()) line = input.readLine() ?: return
                if (!line.startsWith("--frame")) continue
                var length = -1
                var viewport = "0x0"
                var view = "horizontal"
                var scroll = "0/0"
                while (true) {
                    val header = input.readLine() ?: return
                    if (header.isEmpty()) break
                    val name = header.substringBefore(':').trim().lowercase()
                    val value = header.substringAfter(':').trim()
                    if (name == "content-length") length = value.toInt()
                    if (name == "x-viewport") viewport = value
                    if (name == "x-view") view = value
                    if (name == "x-scroll") scroll = value
                }
                if (length <= 0) continue
                val bytes = input.readExactly(length)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                val (w, h) = viewport.split('x').map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 0 }) to (it.getOrElse(1) { 0 }) }
                val (sy, ch) = scroll.split('/').map { it.toFloatOrNull() ?: 0f }.let { it.getOrElse(0) { 0f } to it.getOrElse(1) { 0f } }
                onFrame(BrowserFrame(tabId, bitmap, w, h, view == "vertical", sy, ch))
            }
        }
    }

    fun close() = connection.disconnect()
}

private fun InputStream.readLine(): String? {
    val out = StringBuilder()
    while (true) {
        val b = read()
        if (b == -1) return if (out.isEmpty()) null else out.toString()
        if (b == '\n'.code) return out.toString().trimEnd('\r')
        out.append(b.toChar())
    }
}

private fun InputStream.readExactly(n: Int): ByteArray {
    val buffer = ByteArray(n)
    var read = 0
    while (read < n) {
        val got = read(buffer, read, n - read)
        if (got < 0) throw IOException("stream ended inside a frame")
        read += got
    }
    return buffer
}

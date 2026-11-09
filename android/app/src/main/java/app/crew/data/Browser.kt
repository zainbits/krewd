package app.crew.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import org.json.JSONObject

data class BrowserTab(val id: String, val title: String, val url: String) {
    val host: String get() = url.substringAfter("://").substringBefore('/').removePrefix("www.")
}

data class BrowserTabs(val available: Boolean, val reason: String?, val tabs: List<BrowserTab>)

/** One frame of the agents' browser: the picture and the page viewport it shows, in CSS pixels. */
data class BrowserFrame(val bitmap: Bitmap, val viewportWidth: Int, val viewportHeight: Int)

fun CrewApi.browserTabs(): BrowserTabs {
    val o = JSONObject(rawCall("GET", "/api/browser/tabs"))
    return BrowserTabs(
        available = o.optBoolean("available"),
        reason = o.optStringOrNull("reason"),
        tabs = o.optJSONArray("tabs").objects().map { BrowserTab(it.optString("id"), it.optString("title"), it.optString("url")) },
    )
}

/** Sends a tap, scroll, key, typed text or navigation to the tab. Typed text is not kept anywhere. */
fun CrewApi.browserInput(tabId: String, event: JSONObject) {
    rawCall("POST", "/api/browser/tabs/$tabId/input", event)
}

/**
 * Reads the server's multipart JPEG stream for one tab and calls [onFrame] for each frame until the
 * stream ends or [connection] is disconnected from another thread.
 */
class FrameStream(val connection: HttpURLConnection) {
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
                while (true) {
                    val header = input.readLine() ?: return
                    if (header.isEmpty()) break
                    val name = header.substringBefore(':').trim().lowercase()
                    val value = header.substringAfter(':').trim()
                    if (name == "content-length") length = value.toInt()
                    if (name == "x-viewport") viewport = value
                }
                if (length <= 0) continue
                val bytes = input.readExactly(length)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                val (w, h) = viewport.split('x').map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 0 }) to (it.getOrElse(1) { 0 }) }
                onFrame(BrowserFrame(bitmap, w, h))
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

package app.crew.data

import android.content.Context
import app.crew.constants.Timing
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

enum class Route(val label: String) { Lan("LAN"), Tailnet("Tailscale") }

enum class NetworkChoice { Auto, Lan, Tailnet }

data class ConnectionSettings(
    val lanUrl: String,
    val tailnetUrl: String,
    val token: String,
    val network: NetworkChoice,
) {
    val configured: Boolean get() = token.isNotBlank() && (lanUrl.isNotBlank() || tailnetUrl.isNotBlank())
}

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("crew", Context.MODE_PRIVATE)

    fun load() = ConnectionSettings(
        lanUrl = prefs.getString("lan_url", DEFAULT_LAN)!!,
        tailnetUrl = prefs.getString("tailnet_url", DEFAULT_TAILNET)!!,
        token = prefs.getString("token", "")!!,
        network = NetworkChoice.entries.getOrElse(prefs.getInt("network", 0)) { NetworkChoice.Auto },
    )

    fun save(settings: ConnectionSettings) {
        prefs.edit()
            .putString("lan_url", settings.lanUrl.trim().trimEnd('/'))
            .putString("tailnet_url", settings.tailnetUrl.trim().trimEnd('/'))
            .putString("token", settings.token.trim())
            .putInt("network", settings.network.ordinal)
            .apply()
    }

    /** The owner's last picks per agent (engine id): applied to that agent's new tasks. */
    fun engine(id: String): EngineDefaults = prefs.getString("engine_$id", null)?.let(EngineDefaults::parse) ?: EngineDefaults()

    fun updateEngine(id: String, change: (EngineDefaults) -> EngineDefaults) {
        prefs.edit().putString("engine_$id", change(engine(id)).toJson()).apply()
    }

    /** The agent picked last on the new-task card. */
    fun lastEngine(): String? = prefs.getString("last_engine", null)

    fun setLastEngine(id: String) = prefs.edit().putString("last_engine", id).apply()

    fun vertical(): Boolean = prefs.getBoolean("browser_vertical", false)

    fun setVertical(on: Boolean) = prefs.edit().putBoolean("browser_vertical", on).apply()

    /** High quality live view: 60 fps and sharper frames, at about three times the data. Off: 30 fps. */
    fun highQuality(): Boolean = prefs.getBoolean("browser_high_quality", false)

    fun setHighQuality(on: Boolean) = prefs.edit().putBoolean("browser_high_quality", on).apply()

    /** Cursor (true) or touch input for the browser, remembered per agent, with the last pick as the fallback. */
    fun cursorMode(engineId: String?): Boolean =
        engineId?.let { engine(it).cursor } ?: prefs.getBoolean("browser_cursor", true)

    fun setCursorMode(engineId: String?, cursor: Boolean) {
        prefs.edit().putBoolean("browser_cursor", cursor).apply()
        if (engineId != null) updateEngine(engineId) { it.copy(cursor = cursor) }
    }

    companion object {
        const val DEFAULT_LAN = ""
        const val DEFAULT_TAILNET = ""
    }
}

/** One agent's remembered settings: its config picks (model, effort, mode...), legacy mode, auto-approve and browser input. */
data class EngineDefaults(
    val config: Map<String, String> = emptyMap(),
    val mode: String? = null,
    val autoApprove: Boolean = false,
    val cursor: Boolean? = null,
) {
    fun toJson(): String = JSONObject()
        .put("config", JSONObject(config))
        .put("mode", mode ?: JSONObject.NULL)
        .put("auto_approve", autoApprove)
        .put("cursor", cursor ?: JSONObject.NULL)
        .toString()

    companion object {
        fun parse(text: String): EngineDefaults = runCatching {
            val o = JSONObject(text)
            val c = o.optJSONObject("config") ?: JSONObject()
            EngineDefaults(
                config = c.keys().asSequence().associateWith { c.optString(it) },
                mode = o.optStringOrNull("mode"),
                autoApprove = o.optBoolean("auto_approve"),
                cursor = if (o.isNull("cursor") || !o.has("cursor")) null else o.optBoolean("cursor"),
            )
        }.getOrDefault(EngineDefaults())
    }
}

class ApiException(val code: Int, message: String) : IOException(message)

/** Blocking HTTP client for the Crew server; call it from Dispatchers.IO. */
class CrewApi(val baseUrl: String, private val token: String, val route: Route) {

    fun health(timeoutMs: Int = Timing.CONNECT_TIMEOUT_MS): JSONObject =
        JSONObject(call("GET", "/api/health", null, timeoutMs, timeoutMs, auth = false))

    fun state(): ServerState {
        val o = obj("GET", "/api/state")
        return ServerState(
            lastSeq = o.optLong("last_seq"),
            engines = o.optJSONArray("engines").objects().map {
                EngineInfo(
                    id = it.optString("id"),
                    name = it.optString("name"),
                    isDefault = it.optBoolean("default"),
                    agent = AgentInfo.from(it.optJSONObject("agent")),
                    config = ConfigOption.list(it.optJSONArray("config_options")),
                    steering = it.optBoolean("steering"),
                )
            },
            workdir = o.optString("workdir"),
        )
    }

    fun sessions(): List<SessionInfo> = arr("GET", "/api/sessions").objects().map(SessionInfo::from)

    fun createSession(
        text: String,
        autoApprove: Boolean,
        engine: String?,
        config: Map<String, String>,
        attachments: List<String> = emptyList(),
        tabs: List<BrowserTab> = emptyList(),
        mode: String? = null,
    ): SessionInfo {
        val body = JSONObject().put("text", text).put("auto_approve", autoApprove)
        if (attachments.isNotEmpty()) body.put("attachments", JSONArray(attachments))
        if (tabs.isNotEmpty()) body.put("tabs", tabsJson(tabs))
        if (mode != null) body.put("mode", mode)
        if (engine != null) body.put("engine", engine)
        if (config.isNotEmpty()) body.put("config", JSONObject(config))
        return SessionInfo.from(obj("POST", "/api/sessions", body))
    }

    fun updateSession(id: String, changes: JSONObject): SessionInfo =
        SessionInfo.from(obj("PATCH", "/api/sessions/$id", changes))

    /**
     * Sends a message. [clientId] makes a resend safe (the server keeps one copy); [delivery] is steer,
     * queue or interrupt, or null for the server's default. Returns the stored message.
     */
    fun send(id: String, text: String, attachments: List<String>, tabs: List<BrowserTab>, delivery: String?, clientId: String): JSONObject {
        val body = JSONObject().put("text", text).put("client_id", clientId)
        if (attachments.isNotEmpty()) body.put("attachments", JSONArray(attachments))
        if (tabs.isNotEmpty()) body.put("tabs", tabsJson(tabs))
        if (delivery != null) body.put("delivery", delivery)
        return obj("POST", "/api/sessions/$id/messages", body).optJSONObject("message") ?: JSONObject()
    }

    private fun tabsJson(tabs: List<BrowserTab>) =
        JSONArray(tabs.map { JSONObject().put("id", it.id).put("title", it.title).put("url", it.url) })

    /** Emergency restart of the server and its agents; it is back after about 10 to 15 seconds. */
    fun restartServer() {
        obj("POST", "/api/admin/restart", JSONObject())
    }

    fun deleteSession(id: String) {
        obj("DELETE", "/api/sessions/$id")
    }

    fun cancel(id: String) {
        obj("POST", "/api/sessions/$id/cancel", JSONObject())
    }

    fun sessionEvents(id: String, after: Long): List<Event> =
        obj("GET", "/api/sessions/$id/events?after=$after").optJSONArray("events").objects().map(Event::from)

    /** Long-poll: returns as soon as there are events after [after], or empty after the wait. */
    fun events(after: Long): Pair<List<Event>, Long> {
        val wait = Timing.LONG_POLL_SECONDS
        val o = JSONObject(call("GET", "/api/events?after=$after&wait=$wait", null, Timing.CONNECT_TIMEOUT_MS, wait * 1000 + Timing.READ_MARGIN_MS))
        return o.optJSONArray("events").objects().map(Event::from) to o.optLong("last_seq", after)
    }

    fun approvals(): List<Approval> = arr("GET", "/api/approvals").objects().map(Approval::from)

    fun decide(id: String, optionId: String): Approval =
        Approval.from(obj("POST", "/api/approvals/$id", JSONObject().put("option_id", optionId)))

    /** Opens the live JPEG stream of one browser tab; read it with [FrameStream.read] off the main thread. */
    fun openBrowserStream(tabId: String, high: Boolean = false): FrameStream {
        val quality = if (high) "high" else "standard"
        val conn = URL("$baseUrl/api/browser/tabs/$tabId/stream?quality=$quality").openConnection() as HttpURLConnection
        conn.connectTimeout = Timing.CONNECT_TIMEOUT_MS
        conn.readTimeout = 30_000 // the server repeats the last frame every 10 s
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "multipart/x-mixed-replace")
        return FrameStream(conn, tabId)
    }

    /** Streams a file to the server (no size limit in memory). Returns the upload id. */
    fun upload(name: String, mime: String?, input: java.io.InputStream): String {
        val conn = URL("$baseUrl/api/uploads?name=" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = Timing.CONNECT_TIMEOUT_MS
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setChunkedStreamingMode(64 * 1024)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", mime ?: "application/octet-stream")
            conn.outputStream.use { out -> input.use { it.copyTo(out, 64 * 1024) } }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw ApiException(code, detail?.takeIf { it.isNotBlank() } ?: "Upload failed (HTTP $code)")
            }
            return JSONObject(text).getString("id")
        } finally {
            conn.disconnect()
        }
    }

    /** Streams a file an agent handed back (GET /api/files) into [out]. */
    fun downloadFile(path: String, out: java.io.OutputStream) {
        val conn = URL("$baseUrl/api/files?path=" + java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20"))
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = Timing.CONNECT_TIMEOUT_MS
            conn.readTimeout = 120_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code !in 200..299) {
                throw ApiException(code, if (code == 404) "The file is no longer there" else "Download failed (HTTP $code)")
            }
            conn.inputStream.use { it.copyTo(out, 64 * 1024) }
        } finally {
            conn.disconnect()
        }
    }

    internal fun rawCall(method: String, path: String, body: JSONObject? = null): String = call(method, path, body)

    private fun obj(method: String, path: String, body: JSONObject? = null) =
        JSONObject(call(method, path, body))

    private fun arr(method: String, path: String) = JSONArray(call(method, path, null))

    private fun call(
        method: String,
        path: String,
        body: JSONObject?,
        connectTimeoutMs: Int = Timing.CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = 20_000,
        auth: Boolean = true,
    ): String {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (auth) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw ApiException(code, detail?.takeIf { it.isNotBlank() } ?: "HTTP $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Picks the route to use. Auto tries the LAN first, since it is direct, then Tailscale. */
        fun resolve(settings: ConnectionSettings): CrewApi {
            val candidates = when (settings.network) {
                NetworkChoice.Lan -> listOf(Route.Lan)
                NetworkChoice.Tailnet -> listOf(Route.Tailnet)
                NetworkChoice.Auto -> listOf(Route.Lan, Route.Tailnet)
            }
            var last: Exception? = null
            for (route in candidates) {
                val url = if (route == Route.Lan) settings.lanUrl else settings.tailnetUrl
                if (url.isBlank()) continue
                val api = CrewApi(url, settings.token, route)
                try {
                    api.health(if (settings.network == NetworkChoice.Auto && route == Route.Lan) 1_500 else Timing.CONNECT_TIMEOUT_MS)
                    return api
                } catch (e: Exception) {
                    last = e
                }
            }
            throw last ?: IOException("No server address set")
        }
    }
}

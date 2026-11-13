package app.crew.data

import android.content.Context
import app.crew.constants.Timing
import java.io.IOException
import java.net.HttpURLConnection
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the server is and how to trust it: addresses tried in order (home network, Tailscale, a domain...),
 * the token, and the pin of the server's own certificate.
 */
data class ConnectionSettings(
    val addresses: List<String>,
    val token: String,
    val pin: String,
) {
    val configured: Boolean get() = token.isNotBlank() && addresses.isNotEmpty()

    companion object {
        /** Addresses from text: one per line or comma, trimmed, without a trailing slash; http is upgraded. */
        fun parseAddresses(text: String): List<String> =
            text.split('\n', ',').map { normalize(it) }.filter { it.isNotEmpty() }.distinct()

        fun normalize(address: String): String {
            var a = address.trim().trimEnd('/')
            if (a.isEmpty()) return a
            if (!a.contains("://")) a = "https://$a"
            // Crew serves HTTPS on every network address, and the app never sends anything in plain HTTP.
            if (a.startsWith("http://")) a = "https://" + a.removePrefix("http://")
            return a
        }

        /** A short name for an address: "Home" (private or .local), "Tailscale" (.ts.net), else its host. */
        fun label(address: String): String {
            val host = runCatching { java.net.URI(address).host }.getOrNull().orEmpty()
            return when {
                host.endsWith(".ts.net") -> "Tailscale"
                host.endsWith(".local") || isPrivate(host) -> "Home"
                host.isNotEmpty() -> host
                else -> address
            }
        }

        private fun isPrivate(host: String): Boolean {
            val p = host.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4) return false
            return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
        }
    }
}

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("crew", Context.MODE_PRIVATE)

    fun load(): ConnectionSettings {
        // Before 0.14 there were two fields, a home address (plain HTTP) and a Tailscale address.
        val addresses = prefs.getString("addresses", null)?.let(ConnectionSettings::parseAddresses)
            ?: listOfNotNull(prefs.getString("lan_url", null), prefs.getString("tailnet_url", null))
                .flatMap(ConnectionSettings::parseAddresses).distinct()
        return ConnectionSettings(
            addresses = addresses,
            token = prefs.getString("token", "")!!,
            pin = prefs.getString("pin", "")!!,
        )
    }

    fun save(settings: ConnectionSettings) {
        prefs.edit()
            .putString("addresses", settings.addresses.joinToString("\n"))
            .putString("token", settings.token.trim())
            .putString("pin", settings.pin.trim())
            .remove("lan_url").remove("tailnet_url").remove("network")
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

    /** The picture-in-picture size the owner picked: width (dp) for horizontal tabs, height for vertical ones. */
    fun pipSize(wide: Boolean): Float = prefs.getFloat(if (wide) "pip_wide_width" else "pip_tall_height", if (wide) 180f else 240f)

    fun setPipSize(wide: Boolean, dp: Float) = prefs.edit().putFloat(if (wide) "pip_wide_width" else "pip_tall_height", dp).apply()

    /** Saved prompts for the new-task sheet, newest first. */
    fun savedPrompts(): List<String> = runCatching {
        org.json.JSONArray(prefs.getString("saved_prompts", "[]")).strings()
    }.getOrDefault(emptyList())

    fun setSavedPrompts(list: List<String>) =
        prefs.edit().putString("saved_prompts", org.json.JSONArray(list).toString()).apply()

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
class CrewApi(val baseUrl: String, private val token: String, private val pin: String) {
    /** How this server was checked on the first request ([probe]). */
    @Volatile var verified: Verified = Verified.Plain
        private set

    /** Encrypted and checked (pinned or vouched for by an authority): keys and secrets may go over it. */
    val secure: Boolean get() = verified != Verified.Plain

    val label: String get() = ConnectionSettings.label(baseUrl)

    fun health(timeoutMs: Int = Timing.CONNECT_TIMEOUT_MS): JSONObject =
        JSONObject(call("GET", "/api/health", null, timeoutMs, timeoutMs, auth = false))

    /** The first request to an address: answers health and records how the server was checked. */
    fun probe(timeoutMs: Int): JSONObject {
        val conn = open("$baseUrl/api/health")
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(code, "HTTP $code")
            verified = Tls.verifiedBy(conn, pin)
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** The pin of the server's own certificate (GET /api/tls), or null when it has none. */
    fun tlsPin(): String? = obj("GET", "/api/tls").optStringOrNull("pin")

    private fun open(url: String): HttpURLConnection = Tls.open(url, pin)

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
                    problem = it.optStringOrNull("problem"),
                    commands = AgentCommand.list(it.optJSONArray("commands")),
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

    /** Move the chat to another agent after the running turn; the current agent writes a handover first. */
    fun switchEngine(id: String, engine: String) {
        obj("POST", "/api/sessions/$id/engine", JSONObject().put("engine", engine))
    }

    /** Compact the chat's agent context after the running turn (the agent's own /compact, or a handover). */
    fun compact(id: String) {
        obj("POST", "/api/sessions/$id/compact", JSONObject())
    }

    // routines

    // profiles and sign-ins (Settings)

    fun profiles(): ProfilesState {
        val o = obj("GET", "/api/profiles")
        return ProfilesState(
            o.optJSONArray("profiles").objects().map(ProfileInfo::from),
            o.optJSONArray("presets").objects().map(ProfilePreset::from),
            o.optBoolean("managed"),
        )
    }

    /** Create (id null) or change a profile. A body with a key must go over Tailscale: see [CrewApi.forKeys]. */
    fun saveProfile(id: String?, fields: JSONObject): ProfileInfo =
        // The server asks the gateway for the key's models first, so this can take a while.
        ProfileInfo.from(JSONObject(call(if (id == null) "POST" else "PATCH", if (id == null) "/api/profiles" else "/api/profiles/$id", fields, readTimeoutMs = 60_000)))

    fun deleteProfile(id: String) {
        call("DELETE", "/api/profiles/$id", null)
    }

    fun logins(): List<AgentLogin> = obj("GET", "/api/agents").optJSONArray("agents").objects().map(AgentLogin::from)

    fun startLogin(engine: String): LoginFlow? = LoginFlow.from(JSONObject(call("POST", "/api/agents/$engine/login", null, readTimeoutMs = 45_000)))

    /** Waits while the agent finishes signing in (up to about a minute and a half). */
    fun submitLoginCode(engine: String, code: String): LoginFlow? =
        LoginFlow.from(JSONObject(call("POST", "/api/agents/$engine/login/code", JSONObject().put("code", code), readTimeoutMs = 120_000)))

    fun cancelLogin(engine: String) {
        call("DELETE", "/api/agents/$engine/login", null)
    }

    fun logout(engine: String) {
        call("POST", "/api/agents/$engine/logout", null, readTimeoutMs = 60_000)
    }

    /** Run an agent on a profile (null: its sign-in account); its chats and routines follow. */
    fun setAgentProfile(engine: String, profile: String?) {
        call("PUT", "/api/agents/$engine/profile", JSONObject().put("profile", profile ?: JSONObject.NULL))
    }

    fun routines(): List<Routine> = arr("GET", "/api/routines").objects().map(Routine::from)

    fun routine(id: String): Routine = Routine.from(obj("GET", "/api/routines/$id"))

    fun createRoutine(fields: JSONObject): Routine = Routine.from(obj("POST", "/api/routines", fields))

    fun updateRoutine(id: String, changes: JSONObject): Routine = Routine.from(obj("PATCH", "/api/routines/$id", changes))

    fun deleteRoutine(id: String) {
        obj("DELETE", "/api/routines/$id")
    }

    fun runRoutine(id: String, trigger: String? = null): RoutineRun =
        RoutineRun.from(obj("POST", "/api/routines/$id/run", JSONObject().apply { if (trigger != null) put("trigger", trigger) }))

    fun setRoutineNotes(id: String, notes: String): Routine =
        Routine.from(obj("PUT", "/api/routines/$id/notes", JSONObject().put("notes", notes)))

    fun cancelFollowUp(id: String, triggerId: String) {
        obj("DELETE", "/api/routines/$id/later/$triggerId")
    }

    fun previewSchedule(schedule: String, timezone: String?): SchedulePreview = try {
        val body = JSONObject().put("schedule", schedule)
        if (timezone != null) body.put("timezone", timezone)
        val o = obj("POST", "/api/routines/preview", body)
        SchedulePreview(
            o.optString("schedule_text"),
            o.optJSONArray("next_runs")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            null,
        )
    } catch (e: ApiException) {
        SchedulePreview(null, emptyList(), e.message)
    }

    fun approvals(): List<Approval> = arr("GET", "/api/approvals").objects().map(Approval::from)

    fun decide(id: String, optionId: String): Approval =
        Approval.from(obj("POST", "/api/approvals/$id", JSONObject().put("option_id", optionId)))

    /** Opens the live JPEG stream of one browser tab; read it with [FrameStream.read] off the main thread. */
    fun openBrowserStream(tabId: String, high: Boolean = false): FrameStream {
        val quality = if (high) "high" else "standard"
        val conn = open("$baseUrl/api/browser/tabs/$tabId/stream?quality=$quality")
        conn.connectTimeout = Timing.CONNECT_TIMEOUT_MS
        conn.readTimeout = 30_000 // the server repeats the last frame every 10 s
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "multipart/x-mixed-replace")
        return FrameStream(conn, tabId)
    }

    /** Streams a file to the server (no size limit in memory). Returns the upload id. */
    fun upload(name: String, mime: String?, input: java.io.InputStream): String {
        val conn = open("$baseUrl/api/uploads?name=" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
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
        val conn = open("$baseUrl/api/files?path=" + java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20"))
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

    /** An image from a tool result (GET /api/tool-images/{name}). */
    fun toolImage(name: String): ByteArray {
        val conn = open("$baseUrl/api/tool-images/" + java.net.URLEncoder.encode(name, "UTF-8"))
        try {
            conn.connectTimeout = Timing.CONNECT_TIMEOUT_MS
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(code, if (code == 404) "The image is no longer there" else "Image failed (HTTP $code)")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** A small JPEG of a browser tab (GET /api/browser/tabs/{id}/snapshot), [width] pixels wide. */
    fun tabSnapshot(tabId: String, width: Int): ByteArray {
        val conn = open("$baseUrl/api/browser/tabs/" + java.net.URLEncoder.encode(tabId, "UTF-8") + "/snapshot?width=$width")
        try {
            conn.connectTimeout = Timing.CONNECT_TIMEOUT_MS
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(code, "Snapshot failed (HTTP $code)")
            return conn.inputStream.use { it.readBytes() }
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
        val conn = open(baseUrl + path)
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
        /** A client for requests that carry a key or a secret: only over a checked HTTPS connection. */
        fun forKeys(current: CrewApi, settings: ConnectionSettings): CrewApi {
            if (current.secure) return current
            for (address in settings.addresses) {
                val api = CrewApi(address, settings.token, settings.pin)
                if (runCatching { api.probe(Timing.CONNECT_TIMEOUT_MS) }.isSuccess && api.secure) return api
            }
            throw ApiException(0, "Keys only go over HTTPS, and no address answered over HTTPS")
        }

        /** The first of [addresses] that answers, tried in order; all but the last get a short timeout. */
        fun resolve(settings: ConnectionSettings, addresses: List<String> = settings.addresses): CrewApi {
            var last: Exception? = null
            addresses.forEachIndexed { i, address ->
                val api = CrewApi(address, settings.token, settings.pin)
                try {
                    api.probe(if (i < addresses.lastIndex) 2_000 else Timing.CONNECT_TIMEOUT_MS)
                    return api
                } catch (e: Exception) {
                    last = e
                }
            }
            throw last ?: IOException("No server address set")
        }
    }
}

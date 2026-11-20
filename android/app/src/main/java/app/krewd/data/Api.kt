package app.krewd.data

import android.content.Context
import app.krewd.constants.Timing
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
    /** Set once this phone is paired with the owner's security key: it then signs in with its own keys. */
    val deviceId: String = "",
) {
    val paired: Boolean get() = deviceId.isNotBlank()
    val configured: Boolean get() = (token.isNotBlank() || paired) && addresses.isNotEmpty()

    companion object {
        /** Addresses from text: one per line or comma, trimmed, without a trailing slash; http is upgraded. */
        fun parseAddresses(text: String): List<String> =
            text.split('\n', ',').map { normalize(it) }.filter { it.isNotEmpty() }.distinct()

        fun normalize(address: String): String {
            var a = address.trim().trimEnd('/')
            if (a.isEmpty()) return a
            if (!a.contains("://")) a = "https://$a"
            // Krewd serves HTTPS on every network address, and the app never sends anything in plain HTTP.
            if (a.startsWith("http://")) a = "https://" + a.removePrefix("http://")
            return a
        }

        /** A short name for an address: "Home" (private or .local), "Tailscale" (.ts.net or 100.64.0.0/10), else its host. */
        fun label(address: String): String {
            val host = runCatching { java.net.URI(address).host }.getOrNull().orEmpty()
            return when {
                host.endsWith(".ts.net") || isTailscale(host) -> "Tailscale"
                host.endsWith(".local") || isPrivate(host) -> "Home"
                host.isNotEmpty() -> host
                else -> address
            }
        }

        /** Tailscale's own addresses: 100.64.0.0/10 (shared address space). */
        private fun isTailscale(host: String): Boolean {
            val p = host.split('.').mapNotNull { it.toIntOrNull() }
            return p.size == 4 && p[0] == 100 && p[1] in 64..127
        }

        private fun isPrivate(host: String): Boolean {
            val p = host.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4) return false
            return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
        }
    }
}

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("krewd", Context.MODE_PRIVATE)

    /** Unsent composer text, per chat and for the new-task sheet. Phone only, never synced to the server. */
    private val drafts = context.getSharedPreferences("krewd_drafts", Context.MODE_PRIVATE)

    /** Called with (server key, value) whenever a synced setting changes here, to keep it on the server too. */
    @Volatile var onChange: ((String, Any?) -> Unit)? = null

    private fun synced(key: String, value: Any?) = onChange?.invoke(key, value)

    /** Settings kept on the server (see [apply]): the app's own picks, not this phone's connection or keys. */
    fun syncedValues(): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        prefs.all.forEach { (k, v) ->
            when {
                k.startsWith("engine_") && v is String -> out["engine." + k.removePrefix("engine_")] = JSONObject(v)
                k == "saved_prompts" && v is String -> out[k] = org.json.JSONArray(v)
                k in SYNCED && v != null -> out[k] = v
            }
        }
        return out
    }

    /** The server's copy wins (a new phone starts where the old one was); nothing is sent back for it. */
    fun apply(server: JSONObject) {
        val edit = prefs.edit()
        server.keys().forEach { key ->
            val v = server.get(key)
            when {
                key.startsWith("engine.") && v is JSONObject -> edit.putString("engine_" + key.removePrefix("engine."), v.toString())
                key == "saved_prompts" && v is org.json.JSONArray -> edit.putString(key, v.toString())
                key == "last_engine" && v is String -> edit.putString(key, v)
                key in BOOLEANS && v is Boolean -> edit.putBoolean(key, v)
                key in FLOATS && v is Number -> edit.putFloat(key, v.toFloat())
            }
        }
        edit.apply()
    }

    fun load(): ConnectionSettings {
        // Before 0.14 there were two fields, a home address (plain HTTP) and a Tailscale address.
        val addresses = prefs.getString("addresses", null)?.let(ConnectionSettings::parseAddresses)
            ?: listOfNotNull(prefs.getString("lan_url", null), prefs.getString("tailnet_url", null))
                .flatMap(ConnectionSettings::parseAddresses).distinct()
        return ConnectionSettings(
            addresses = addresses,
            token = prefs.getString("token", "")!!,
            pin = prefs.getString("pin", "")!!,
            deviceId = prefs.getString("device_id", "")!!,
        )
    }

    fun save(settings: ConnectionSettings) {
        prefs.edit()
            .putString("addresses", settings.addresses.joinToString("\n"))
            .putString("token", settings.token.trim())
            .putString("pin", settings.pin.trim())
            .putString("device_id", settings.deviceId.trim())
            .remove("lan_url").remove("tailnet_url").remove("network")
            .apply()
    }

    /** The owner's last picks per agent (engine id): applied to that agent's new tasks. */
    fun engine(id: String): EngineDefaults = prefs.getString("engine_$id", null)?.let(EngineDefaults::parse) ?: EngineDefaults()

    fun updateEngine(id: String, change: (EngineDefaults) -> EngineDefaults) {
        val next = change(engine(id)).toJson()
        prefs.edit().putString("engine_$id", next).apply()
        synced("engine.$id", JSONObject(next))
    }

    /** The agent picked last on the new-task card. */
    fun lastEngine(): String? = prefs.getString("last_engine", null)

    fun setLastEngine(id: String) {
        prefs.edit().putString("last_engine", id).apply()
        synced("last_engine", id)
    }

    /** The picture-in-picture size the owner picked: width (dp) for horizontal tabs, height for vertical ones. */
    fun pipSize(wide: Boolean): Float = prefs.getFloat(if (wide) "pip_wide_width" else "pip_tall_height", if (wide) 180f else 240f)

    fun setPipSize(wide: Boolean, dp: Float) {
        val key = if (wide) "pip_wide_width" else "pip_tall_height"
        prefs.edit().putFloat(key, dp).apply()
        synced(key, dp)
    }

    /** Saved prompts for the new-task sheet, newest first. */
    fun savedPrompts(): List<String> = runCatching {
        org.json.JSONArray(prefs.getString("saved_prompts", "[]")).strings()
    }.getOrDefault(emptyList())

    fun setSavedPrompts(list: List<String>) {
        prefs.edit().putString("saved_prompts", org.json.JSONArray(list).toString()).apply()
        synced("saved_prompts", org.json.JSONArray(list))
    }

    /** High quality live view: 60 fps and sharper frames, at about three times the data. Off: 30 fps. */
    fun highQuality(): Boolean = prefs.getBoolean("browser_high_quality", false)

    fun setHighQuality(on: Boolean) {
        prefs.edit().putBoolean("browser_high_quality", on).apply()
        synced("browser_high_quality", on)
    }

    /** Swipe a part of an agent's reply to the right to leave a margin note on it (tap and the selection menu always work). */
    fun swipeToNote(): Boolean = prefs.getBoolean("swipe_to_note", true)

    fun setSwipeToNote(on: Boolean) {
        prefs.edit().putBoolean("swipe_to_note", on).apply()
        synced("swipe_to_note", on)
    }

    /** Liquid glass and its stretch on the title bar, composer and nav bar (off: plain panels). */
    fun glassEnabled(): Boolean = prefs.getBoolean("glass_enabled", true)

    fun setGlassEnabled(on: Boolean) {
        prefs.edit().putBoolean("glass_enabled", on).apply()
        synced("glass_enabled", on)
    }

    /** How see-through the chat's liquid glass is: 0 = ultra-clear, 1 = fully tinted. */
    fun glassClarity(): Float = prefs.getFloat("glass_clarity", DEFAULT_GLASS_CLARITY)

    fun setGlassClarity(value: Float) {
        prefs.edit().putFloat("glass_clarity", value).apply()
        synced("glass_clarity", value)
    }

    /** Code blocks open wrapped or scrolling sideways: the last pick of their Wrap button. */
    fun codeWrap(): Boolean = prefs.getBoolean("code_wrap", false)

    fun setCodeWrap(on: Boolean) {
        prefs.edit().putBoolean("code_wrap", on).apply()
        synced("code_wrap", on)
    }

    /** Cursor (true) or touch input for the browser, remembered per agent, with the last pick as the fallback. */
    fun cursorMode(engineId: String?): Boolean =
        engineId?.let { engine(it).cursor } ?: prefs.getBoolean("browser_cursor", true)

    fun setCursorMode(engineId: String?, cursor: Boolean) {
        prefs.edit().putBoolean("browser_cursor", cursor).apply()
        synced("browser_cursor", cursor)
        if (engineId != null) updateEngine(engineId) { it.copy(cursor = cursor) }
    }

    /** Unsent text for [key] (a session id, or [app.krewd.NEW_DRAFT]); empty when there is none. */
    fun textDraft(key: String): String = drafts.getString(key, null).orEmpty()

    fun setTextDraft(key: String, text: String) {
        if (text.isBlank()) drafts.edit().remove(key).apply() else drafts.edit().putString(key, text).apply()
    }

    /** Chats attached with @ to the unsent message for [key], kept with its text. */
    fun chatDraft(key: String): List<ChatRef> = runCatching {
        ChatRef.list(JSONArray(drafts.getString("chats:$key", null) ?: "[]"))
    }.getOrDefault(emptyList())

    fun setChatDraft(key: String, chats: List<ChatRef>) {
        if (chats.isEmpty()) drafts.edit().remove("chats:$key").apply()
        else drafts.edit().putString("chats:$key", JSONArray(chats.map { it.toJson() }).toString()).apply()
    }

    /** Margin notes waiting in a chat's composer. Phone only, like the text. */
    fun noteDraft(key: String): List<ReplyNote> = runCatching {
        ReplyNote.list(JSONArray(drafts.getString("notes:$key", null) ?: "[]"))
    }.getOrDefault(emptyList())

    fun setNoteDraft(key: String, notes: List<ReplyNote>) {
        if (notes.isEmpty()) drafts.edit().remove("notes:$key").apply()
        else drafts.edit().putString("notes:$key", JSONArray(notes.map { it.toJson() }).toString()).apply()
    }

    fun noteDraftKeys(): List<String> = drafts.all.keys.filter { it.startsWith("notes:") }.map { it.removePrefix("notes:") }

    /** Every draft key with chats attached, to load them at start. */
    fun chatDraftKeys(): List<String> = drafts.all.keys.filter { it.startsWith("chats:") }.map { it.removePrefix("chats:") }

    companion object {
        private val SYNCED = setOf(
            "last_engine", "pip_wide_width", "pip_tall_height", "browser_high_quality", "browser_cursor", "swipe_to_note", "code_wrap",
            "glass_clarity", "glass_enabled",
        )
        private val FLOATS = setOf("pip_wide_width", "pip_tall_height", "glass_clarity")
        const val DEFAULT_GLASS_CLARITY = 0.35f
        private val BOOLEANS = setOf("browser_high_quality", "browser_cursor", "swipe_to_note", "code_wrap", "glass_enabled")
    }
}

/** Who the app is to the server: the setup token, or (once paired) this phone, with a short session it renews. */
class Credentials(val token: String, val deviceId: String) {
    @Volatile var session: String? = null
    val paired: Boolean get() = deviceId.isNotBlank()

    companion object {
        fun of(settings: ConnectionSettings) = Credentials(settings.token, settings.deviceId)
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

/** Blocking HTTP client for the Krewd server; call it from Dispatchers.IO. */
class KrewdApi(
    val baseUrl: String,
    private val creds: Credentials,
    private val pin: String,
    /** Only while pairing a phone that does not know the server's certificate yet: see [Tls.Capture]. */
    private val capture: Tls.Capture? = null,
) {
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

    private fun open(url: String): HttpURLConnection = if (capture != null) Tls.openCapturing(url, capture) else Tls.open(url, pin)

    fun captured(): Tls.Capture? = capture

    /** The pin of the certificate this connection saw, which the phone signs (the server checks it is its own). */
    fun pinSeen(): String = when {
        capture != null -> capture.pin.orEmpty()
        verified == Verified.Pin -> pin
        else -> ""
    }

    private fun bearer(): String = if (creds.paired) creds.session ?: renewSession() else creds.token

    /** A new session for this phone: sign a fresh nonce with its device key. */
    fun renewSession(): String = synchronized(creds) {
        creds.session?.let { return it }
        val nonce = nonce("session").getString("nonce")
        val pinSeen = pinSeen()
        val sig = Keys.signWithDevice("krewd-device-v1\nsession\n$nonce\n$pinSeen".toByteArray())
        val body = JSONObject().put("device_id", creds.deviceId).put("nonce", nonce).put("pin_seen", pinSeen)
            .put("sig", android.util.Base64.encodeToString(sig, android.util.Base64.NO_WRAP))
        val session = JSONObject(call("POST", "/api/security/session", body, auth = false)).getString("session")
        creds.session = session
        session
    }

    fun nonce(purpose: String): JSONObject =
        JSONObject(call("POST", "/api/security/nonce", JSONObject().put("purpose", purpose), auth = false))

    /** Adds this phone (see security.py on the server); the first one also sends the setup token. */
    fun pair(body: JSONObject, withToken: Boolean): JSONObject = JSONObject(call(
        "POST", "/api/security/pair", body, auth = false,
        headers = if (withToken && creds.token.isNotBlank()) mapOf("Authorization" to "Bearer ${creds.token}") else emptyMap(),
    ))

    fun securityState(): JSONObject = obj("GET", "/api/security")

    fun revokeDevice(id: String, body: JSONObject) {
        call("POST", "/api/security/devices/$id/revoke", body)
    }

    fun addOwnerKey(body: JSONObject): JSONObject = JSONObject(call("POST", "/api/security/keys", body))

    fun removeOwnerKey(id: String, body: JSONObject): JSONObject =
        JSONObject(call("POST", "/api/security/keys/" + java.net.URLEncoder.encode(id, "UTF-8") + "/remove", body))

    fun serverPrefs(): JSONObject = obj("GET", "/api/prefs")

    fun setPref(key: String, value: Any?) {
        call("PUT", "/api/prefs/" + java.net.URLEncoder.encode(key, "UTF-8"), JSONObject().put("value", value ?: JSONObject.NULL))
    }

    fun secrets(): List<SecretAsk> = arr("GET", "/api/secrets").objects().map(SecretAsk::from)

    /** A request with an exact body and extra headers, for requests the owner's fingerprint signs. */
    fun send(method: String, path: String, text: String, headers: Map<String, String>, readTimeoutMs: Int = 60_000): String =
        call(method, path, null, readTimeoutMs = readTimeoutMs, text = text, headers = headers)

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

    fun archivedSessions(): List<SessionInfo> = arr("GET", "/api/sessions?archived=true").objects().map(SessionInfo::from)

    /** Side sessions, sub-agents and messages to other chats that agents drafted and wait for the owner to send. */
    fun proposals(): List<Proposal> = arr("GET", "/api/proposals?status=pending").objects().map(Proposal::from)

    fun sendProposal(id: String, body: JSONObject): Proposal = Proposal.from(obj("POST", "/api/proposals/$id/send", body))

    fun dismissProposal(id: String): Proposal = Proposal.from(obj("POST", "/api/proposals/$id/dismiss", JSONObject()))

    fun createSession(
        text: String,
        autoApprove: Boolean,
        engine: String?,
        config: Map<String, String>,
        attachments: List<String> = emptyList(),
        tabs: List<BrowserTab> = emptyList(),
        mode: String? = null,
        chats: List<ChatRef> = emptyList(),
        parentId: String? = null,
        returns: Boolean = false,
        title: String? = null,
    ): SessionInfo {
        val body = JSONObject().put("text", text).put("auto_approve", autoApprove)
        if (attachments.isNotEmpty()) body.put("attachments", JSONArray(attachments))
        if (tabs.isNotEmpty()) body.put("tabs", tabsJson(tabs))
        if (chats.isNotEmpty()) body.put("chats", chatsJson(chats))
        if (parentId != null) body.put("parent_id", parentId).put("returns", returns)
        if (!title.isNullOrBlank()) body.put("title", title)
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
    fun send(
        id: String, text: String, attachments: List<String>, tabs: List<BrowserTab>, delivery: String?, clientId: String,
        chats: List<ChatRef> = emptyList(), notes: List<ReplyNote> = emptyList(),
    ): JSONObject {
        val body = JSONObject().put("text", text).put("client_id", clientId)
        if (notes.isNotEmpty()) body.put("notes", JSONArray(notes.map { it.toJson() }))
        if (attachments.isNotEmpty()) body.put("attachments", JSONArray(attachments))
        if (tabs.isNotEmpty()) body.put("tabs", tabsJson(tabs))
        if (chats.isNotEmpty()) body.put("chats", chatsJson(chats))
        if (delivery != null) body.put("delivery", delivery)
        return obj("POST", "/api/sessions/$id/messages", body).optJSONObject("message") ?: JSONObject()
    }

    private fun chatsJson(chats: List<ChatRef>) = JSONArray(chats.map { JSONObject().put("id", it.id) })

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

    /** Create (id null) or change a profile. A body with a key must go over Tailscale: see [KrewdApi.forKeys]. */
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
        conn.setRequestProperty("Authorization", "Bearer ${bearer()}")
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
            conn.setRequestProperty("Authorization", "Bearer ${bearer()}")
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

    /**
     * Streams a file an agent handed back (GET /api/files) into [out], telling [progress] the bytes so far and the
     * total (the response's length, else [size]; 0 when neither is known). [onConnect] gets the connection before
     * anything is read, so the caller can disconnect it to stop a download that is waiting on the network.
     */
    fun downloadFile(
        path: String,
        out: java.io.OutputStream,
        size: Long? = null,
        onConnect: (HttpURLConnection) -> Unit = {},
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val conn = open("$baseUrl/api/files?path=" + java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20"))
        onConnect(conn)
        try {
            conn.connectTimeout = Timing.CONNECT_TIMEOUT_MS
            conn.readTimeout = 120_000
            conn.setRequestProperty("Authorization", "Bearer ${bearer()}")
            val code = conn.responseCode
            if (code !in 200..299) {
                throw ApiException(code, if (code == 404) "The file is no longer there" else "Download failed (HTTP $code)")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: size ?: 0L
            conn.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                progress(0, total)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    progress(done, total)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** A file sent with a message (GET /api/attachments/{name}). */
    fun attachment(name: String): ByteArray {
        val conn = open("$baseUrl/api/attachments/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
        try {
            conn.connectTimeout = Timing.CONNECT_TIMEOUT_MS
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer ${bearer()}")
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(code, if (code == 404) "The file is no longer there" else "Image failed (HTTP $code)")
            return conn.inputStream.use { it.readBytes() }
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
            conn.setRequestProperty("Authorization", "Bearer ${bearer()}")
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
            conn.setRequestProperty("Authorization", "Bearer ${bearer()}")
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
        text: String? = null,
        headers: Map<String, String> = emptyMap(),
        retried: Boolean = false,
    ): String {
        val conn = open(baseUrl + path)
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (auth) conn.setRequestProperty("Authorization", "Bearer ${bearer()}")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val payload = text ?: body?.toString()
            if (payload != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(payload.toByteArray()) }
            }
            val code = conn.responseCode
            if (code == 401 && auth && creds.paired && !retried) {
                // The session ran out (or the server restarted): get a new one with the phone's key, once.
                creds.session = null
                conn.disconnect()
                return call(method, path, body, connectTimeoutMs, readTimeoutMs, auth, text, headers, retried = true)
            }
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
        fun forKeys(current: KrewdApi, settings: ConnectionSettings, creds: Credentials): KrewdApi {
            if (current.secure) return current
            for (address in settings.addresses) {
                val api = KrewdApi(address, creds, settings.pin)
                if (runCatching { api.probe(Timing.CONNECT_TIMEOUT_MS) }.isSuccess && api.secure) return api
            }
            throw ApiException(0, "Keys only go over HTTPS, and no address answered over HTTPS")
        }

        /** The first of [addresses] that answers, tried in order; all but the last get a short timeout. */
        fun resolve(settings: ConnectionSettings, creds: Credentials, addresses: List<String> = settings.addresses): KrewdApi {
            var last: Exception? = null
            addresses.forEachIndexed { i, address ->
                val api = KrewdApi(address, creds, settings.pin)
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

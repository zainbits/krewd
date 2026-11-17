package app.krewd.data

import org.json.JSONArray
import org.json.JSONObject

data class ModeInfo(val id: String, val name: String, val description: String)

/** A file attached to a message, as stored on the server. */
data class Attachment(val name: String, val path: String, val size: Long, val mime: String) {
    val isImage: Boolean get() = isImageFile(mime, name)

    companion object {
        fun list(a: JSONArray?): List<Attachment> = a.objects().map {
            Attachment(it.optString("name"), it.optString("path"), it.optLong("size"), it.optString("mime"))
        }
    }
}

/** A file picked on the phone for the next message: uploading, ready (has an upload id) or failed. */
data class PendingFile(
    val localId: Long,
    val name: String,
    val size: Long?,
    val uploadId: String? = null,
    val error: String? = null,
    val uri: String? = null,
    val mime: String? = null,
) {
    val uploading: Boolean get() = uploadId == null && error == null
    val isImage: Boolean get() = uri != null && isImageFile(mime, name)
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif")

/** Pictures the phone can decode: by MIME type, or by extension when the type is generic. */
fun isImageFile(mime: String?, name: String): Boolean =
    mime?.startsWith("image/") == true && mime != "image/svg+xml" ||
        (mime.isNullOrBlank() || mime == "application/octet-stream") && name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/** A picture the app can show full screen: picked on this phone, sent with a message, or from a tool result. */
sealed class ImageRef {
    abstract val key: String
    abstract val name: String

    data class Local(val uri: String, override val name: String, val mime: String?) : ImageRef() {
        override val key get() = "local:$uri"
    }

    data class Sent(val file: Attachment) : ImageRef() {
        override val key get() = "sent:${file.path}"
        override val name get() = file.name
    }

    data class Tool(val file: String) : ImageRef() {
        override val key get() = "tool:$file"
        override val name get() = file
    }
}

fun humanSize(bytes: Long?): String = when {
    bytes == null -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}

/** The agent's own name and version, from ACP agentInfo. */
data class AgentInfo(val name: String, val title: String?, val version: String?) {
    val label: String get() = listOfNotNull(title ?: name, version).joinToString(" ")

    companion object {
        fun from(o: JSONObject?): AgentInfo? = o?.let {
            AgentInfo(it.optString("name"), it.optStringOrNull("title"), it.optStringOrNull("version"))
        }
    }
}

data class ConfigChoice(val value: String, val name: String, val description: String?)

/** One agent setting (model, effort, mode, ...) exactly as the agent advertises it over ACP. */
data class ConfigOption(
    val id: String,
    val name: String,
    val description: String?,
    val category: String?,
    val current: String?,
    val choices: List<ConfigChoice>,
) {
    fun nameOf(value: String?): String? = choices.firstOrNull { it.value == value }?.name ?: value
    val currentName: String? get() = nameOf(current)
    val isMode: Boolean get() = category == "mode"

    companion object {
        fun list(a: JSONArray?): List<ConfigOption> = a.objects().map { o ->
            ConfigOption(
                id = o.optString("id"),
                name = o.optString("name", o.optString("id")),
                description = o.optStringOrNull("description"),
                category = o.optStringOrNull("category"),
                current = o.optStringOrNull("current"),
                choices = o.optJSONArray("options").objects().map {
                    ConfigChoice(it.optString("value"), it.optString("name", it.optString("value")), it.optStringOrNull("description"))
                },
            )
        }.filter { it.id.isNotEmpty() && it.choices.isNotEmpty() }

        /** Model first, then effort, then the rest in the agent's order. */
        fun ordered(options: List<ConfigOption>): List<ConfigOption> =
            options.sortedBy { when (it.category) { "model" -> 0; "thought_level" -> 1; "mode" -> 2; else -> 3 } }

        /** Short "Sonnet 5.5 · Low" line for lists. */
        fun summary(options: List<ConfigOption>): String = listOfNotNull(
            options.firstOrNull { it.category == "model" }?.currentName,
            options.firstOrNull { it.category == "thought_level" }?.currentName?.takeIf { it.lowercase() != "default" },
        ).joinToString(" · ")
    }
}

data class SessionInfo(
    val id: String,
    val engine: String,
    val title: String,
    val status: String,
    val mode: String?,
    val modes: List<ModeInfo>,
    val autoApprove: Boolean,
    val lastText: String,
    val pinned: Boolean,
    val archived: Boolean,
    val pendingApprovals: Int,
    val updatedAt: String,
    val contextUsed: Long?,
    val contextSize: Long?,
    val agent: AgentInfo?,
    val config: List<ConfigOption>,
    val modelUsed: String?,
    /** The browser tab the agent last used, seen from the browser side. */
    val browserTab: BrowserTab? = null,
    /** Set when this chat is a routine's own chat (every run of the routine comes here). */
    val routineId: String? = null,
    /** How Compact works here: "native" (the agent's own /compact) or "handover" (a fresh agent session). */
    val compact: String = "handover",
    /** The agent's own slash commands and skills, suggested in the composer. */
    val commands: List<AgentCommand> = emptyList(),
    /** Set on a side session: the chat it was started from. */
    val parentId: String? = null,
    val parentTitle: String? = null,
    /** A side session whose finished replies go back to its parent: a sub-agent. */
    val returns: Boolean = false,
    /** Chats that owe this one a reply (its sub-agents at work, chats it sent a message to). */
    val waitingOn: List<String> = emptyList(),
) {
    val isSide: Boolean get() = parentId != null

    /** "Sub-agent" or "Side" for a side session, null for an ordinary chat. */
    val sideLabel: String? get() = when {
        parentId == null -> null
        returns -> "Sub-agent"
        else -> "Side"
    }

    val contextPercent: Int? get() = if (contextUsed != null && contextSize != null && contextSize > 0) (contextUsed * 100 / contextSize).toInt() else null

    val isBusy: Boolean get() = status == "running" || status == "waiting"

    companion object {
        fun from(o: JSONObject): SessionInfo {
            val modes = o.optJSONObject("modes")?.optJSONArray("availableModes")
            val usage = o.optJSONObject("usage")
            return SessionInfo(
                id = o.getString("id"),
                engine = o.optString("engine"),
                title = o.optString("title").ifBlank { "New task" },
                status = o.optString("status", "idle"),
                mode = o.optStringOrNull("mode"),
                modes = modes.objects().map {
                    ModeInfo(it.optString("id"), it.optString("name", it.optString("id")), it.optString("description"))
                },
                autoApprove = o.optBoolean("auto_approve"),
                lastText = o.optString("last_text"),
                pinned = o.optBoolean("pinned"),
                archived = o.optBoolean("archived"),
                pendingApprovals = o.optInt("pending_approvals"),
                updatedAt = o.optString("updated_at"),
                contextUsed = usage?.optLongOrNull("used"),
                contextSize = usage?.optLongOrNull("size"),
                agent = AgentInfo.from(o.optJSONObject("agent")),
                config = ConfigOption.list(o.optJSONArray("config_options")),
                modelUsed = o.optStringOrNull("model_used"),
                browserTab = o.optJSONObject("browser_tab")?.let {
                    BrowserTab(it.optString("id"), it.optString("title"), it.optString("url"))
                },
                routineId = o.optStringOrNull("routine_id"),
                compact = o.optString("compact", "handover"),
                commands = AgentCommand.list(o.optJSONArray("commands")),
                parentId = o.optStringOrNull("parent_id"),
                parentTitle = o.optStringOrNull("parent_title"),
                returns = o.optBoolean("returns"),
                waitingOn = o.optJSONArray("waiting_on").strings(),
            )
        }
    }
}

/** A Krewd chat attached to a message with @: its id, its title then, and how it relates to the chat it went to. */
data class ChatRef(val id: String, val title: String, val relation: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).apply { relation?.let { put("relation", it) } }

    companion object {
        fun list(a: JSONArray?): List<ChatRef> = a.objects().mapNotNull {
            val id = it.optString("id")
            if (id.isBlank()) null else ChatRef(id, it.optString("title").ifBlank { "Chat" }, it.optStringOrNull("relation"))
        }
    }
}

/**
 * What an agent drafted and only the owner starts: a new side session or sub-agent ([kind] "new"), or a message to
 * another chat ("prompt"). [status]: pending, sent, dismissed, void (its target chat was deleted).
 */
data class Proposal(
    val id: String,
    val sessionId: String,
    val sessionTitle: String?,
    val kind: String,
    val targetId: String?,
    val targetTitle: String?,
    val title: String,
    val text: String,
    val engine: String?,
    val returns: Boolean,
    val status: String,
    val resultId: String?,
    val createdAt: String,
) {
    val pending: Boolean get() = status == "pending"
    val isPrompt: Boolean get() = kind == "prompt"

    /** "a sub-agent", "a side session" or "a message to <chat>". */
    val what: String get() = when {
        isPrompt -> "a message to ${targetTitle ?: "another chat"}"
        returns -> "a sub-agent"
        else -> "a side session"
    }

    companion object {
        fun from(o: JSONObject) = Proposal(
            id = o.optString("id"),
            sessionId = o.optString("session_id"),
            sessionTitle = o.optStringOrNull("session_title"),
            kind = o.optString("kind", "new"),
            targetId = o.optStringOrNull("target_id"),
            targetTitle = o.optStringOrNull("target_title"),
            title = o.optString("title"),
            text = o.optString("text"),
            engine = o.optStringOrNull("engine"),
            returns = o.optBoolean("returns"),
            status = o.optString("status", "pending"),
            resultId = o.optStringOrNull("result_id"),
            createdAt = o.optString("created_at"),
        )
    }
}

/**
 * When a routine runs: a named cron trigger ("check-in", 5-field cron), or a follow-up ("once") that a run
 * asked for as the next attempt of its [parent] trigger.
 */
data class RoutineTrigger(
    val id: String,
    val name: String,
    val kind: String,
    val schedule: String?,
    val scheduleText: String?,
    val enabled: Boolean,
    val maxAttempts: Int,
    val nextRunAt: String?,
    val attempt: Int?,
    val reason: String?,
    val parent: String?,
) {
    val isFollowUp: Boolean get() = kind == "once"

    companion object {
        fun from(o: JSONObject) = RoutineTrigger(
            id = o.optString("id"),
            name = o.optString("name"),
            kind = o.optString("kind", "cron"),
            schedule = o.optStringOrNull("schedule"),
            scheduleText = o.optStringOrNull("schedule_text"),
            enabled = o.optBoolean("enabled", true),
            maxAttempts = o.optInt("max_attempts", 1),
            nextRunAt = o.optStringOrNull("next_run_at"),
            attempt = if (o.isNull("attempt")) null else o.optInt("attempt"),
            reason = o.optStringOrNull("reason"),
            parent = o.optStringOrNull("parent"),
        )
    }
}

/** One run of a routine: it went into the routine's chat as the message at [seq]. */
data class RoutineRun(
    val id: String,
    val sessionId: String?,
    val seq: Long?,
    val trigger: String,
    /** The triggers that fired for this run ("check-out"); "main" for a routine with one schedule. */
    val triggers: List<String> = emptyList(),
    val attempt: Int = 1,
    val maxAttempts: Int = 1,
    val version: Int,
    val status: String,
    val summary: String?,
    val scheduledFor: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val createdAt: String,
) {
    val active: Boolean get() = status == "queued" || status == "running"

    companion object {
        fun from(o: JSONObject) = RoutineRun(
            id = o.getString("id"),
            sessionId = o.optStringOrNull("session_id"),
            seq = o.optLongOrNull("seq"),
            trigger = o.optString("trigger"),
            triggers = o.optJSONArray("triggers")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            attempt = o.optInt("attempt", 1),
            maxAttempts = o.optInt("max_attempts", 1),
            version = o.optInt("version"),
            status = o.optString("status"),
            summary = o.optStringOrNull("summary"),
            scheduledFor = o.optStringOrNull("scheduled_for"),
            startedAt = o.optStringOrNull("started_at"),
            finishedAt = o.optStringOrNull("finished_at"),
            createdAt = o.optString("created_at"),
        )
    }
}

/** Instructions Krewd runs on a schedule, every run in the routine's own chat. */
data class Routine(
    val id: String,
    val title: String,
    val instructions: String,
    val schedule: String,
    val scheduleText: String,
    val timezone: String,
    val sessionId: String?,
    val engine: String,
    val autoApprove: Boolean,
    val enabled: Boolean,
    val version: Int,
    val nextRunAt: String?,
    val nextRuns: List<String>,
    val createdBy: String,
    val lastRun: RoutineRun?,
    val sessionStatus: String?,
    /** Only filled when one routine is loaded with its history. */
    val runs: List<RoutineRun>? = null,
    val triggers: List<RoutineTrigger> = emptyList(),
    /** What the routine's agent learned and keeps between runs. */
    val notes: String = "",
    /** The routine's picks for its agent (model, effort, permission mode...), by ACP config option id. */
    val config: Map<String, String> = emptyMap(),
) {
    val cronTriggers: List<RoutineTrigger> get() = triggers.filterNot { it.isFollowUp }
    val followUps: List<RoutineTrigger> get() = triggers.filter { it.isFollowUp }

    companion object {
        fun from(o: JSONObject) = Routine(
            id = o.getString("id"),
            title = o.optString("title"),
            instructions = o.optString("instructions"),
            schedule = o.optString("schedule"),
            scheduleText = o.optString("schedule_text", o.optString("schedule")),
            timezone = o.optString("timezone"),
            sessionId = o.optStringOrNull("session_id"),
            engine = o.optString("engine"),
            autoApprove = o.optBoolean("auto_approve"),
            enabled = o.optBoolean("enabled", true),
            version = o.optInt("version", 1),
            nextRunAt = o.optStringOrNull("next_run_at"),
            nextRuns = o.optJSONArray("next_runs")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            createdBy = o.optString("created_by", "owner"),
            lastRun = o.optJSONObject("last_run")?.let(RoutineRun::from),
            sessionStatus = o.optStringOrNull("session_status"),
            runs = if (o.has("runs")) o.optJSONArray("runs").objects().map(RoutineRun::from) else null,
            triggers = o.optJSONArray("triggers").objects().map(RoutineTrigger::from),
            notes = o.optString("notes"),
            config = o.optJSONObject("config")?.let { c -> c.keys().asSequence().associateWith { c.optString(it) } } ?: emptyMap(),
        )
    }
}

/** What a schedule means, checked by the server: its reading in words and next times, or the error. */
data class SchedulePreview(val text: String?, val nextRuns: List<String>, val error: String?)

data class ApprovalOption(val id: String, val name: String, val kind: String) {
    val allows: Boolean get() = kind.startsWith("allow")
}

data class Approval(
    val id: String,
    val sessionId: String,
    val title: String,
    val kind: String?,
    val input: String?,
    val options: List<ApprovalOption>,
    val status: String,
    val decidedBy: String?,
) {
    val pending: Boolean get() = status == "pending"

    companion object {
        fun from(o: JSONObject): Approval {
            val tool = o.optJSONObject("tool") ?: JSONObject()
            return Approval(
                id = o.getString("id"),
                sessionId = o.getString("session_id"),
                title = tool.optString("title", "Tool"),
                kind = tool.optStringOrNull("kind"),
                input = tool.optStringOrNull("input"),
                options = o.optJSONArray("options").objects().map {
                    ApprovalOption(it.optString("optionId"), it.optString("name"), it.optString("kind"))
                },
                status = o.optString("status"),
                decidedBy = o.optStringOrNull("decided_by"),
            )
        }
    }
}

data class Event(val seq: Long, val sessionId: String, val kind: String, val payload: JSONObject) {
    companion object {
        fun from(o: JSONObject) = Event(
            seq = o.getLong("seq"),
            sessionId = o.getString("session_id"),
            kind = o.getString("kind"),
            payload = o.optJSONObject("payload") ?: JSONObject(),
        )
    }
}

data class EngineInfo(
    val id: String,
    val name: String,
    val isDefault: Boolean,
    val agent: AgentInfo? = null,
    val config: List<ConfigOption> = emptyList(),
    /** The agent takes messages into a running turn (ACP `_session/steering`). */
    val steering: Boolean = false,
    /** Why the agent cannot run as set up now (its profile has no key, its sign-in account is not signed in). */
    val problem: String? = null,
    /** What the agent last listed as its slash commands and skills, for a new task before its chat exists. */
    val commands: List<AgentCommand> = emptyList(),
)

/**
 * One of an agent's own slash commands or skills (ACP `available_commands_update`). Codex names its skills `$name`;
 * everything else is typed as `/name`. A pick is sent as plain text and the agent runs it.
 */
data class AgentCommand(val name: String, val description: String, val hint: String?) {
    val label: String get() = if (name.startsWith("$")) name else "/$name"

    companion object {
        fun list(a: JSONArray?): List<AgentCommand> = a.objects().mapNotNull {
            val name = it.optString("name")
            if (name.isBlank()) null else AgentCommand(name, it.optString("description"), it.optStringOrNull("hint"))
        }
    }
}

/** A gateway profile managed from Settings. The key itself never comes back from the server. */
data class ProfileInfo(
    val id: String,
    val name: String,
    val openai: String?,
    val anthropic: String?,
    val keySet: Boolean,
    val keyHint: String?,
    /** The agents running on it. */
    val agents: List<String>,
) {
    companion object {
        fun from(o: JSONObject) = ProfileInfo(
            id = o.optString("id"),
            name = o.optString("name"),
            openai = o.optStringOrNull("openai"),
            anthropic = o.optStringOrNull("anthropic"),
            keySet = o.optBoolean("key_set"),
            keyHint = o.optStringOrNull("key_hint"),
            agents = o.optJSONArray("agents").strings(),
        )
    }
}

/** A ready-made gateway: only its key is asked for. */
data class ProfilePreset(val id: String, val name: String, val openai: String?, val anthropic: String?) {
    companion object {
        fun from(o: JSONObject) = ProfilePreset(o.optString("id"), o.optString("name"), o.optStringOrNull("openai"), o.optStringOrNull("anthropic"))
    }
}

data class ProfilesState(val profiles: List<ProfileInfo> = emptyList(), val presets: List<ProfilePreset> = emptyList(), val managed: Boolean = false)

/** A sign-in in progress: the page to open, and a code to type there (Codex) or a code to paste back (Claude Code). */
data class LoginFlow(val state: String, val url: String?, val code: String?, val paste: Boolean, val error: String?, val expiresIn: Int) {
    val active: Boolean get() = state == "starting" || state == "waiting" || state == "finishing"

    companion object {
        fun from(o: JSONObject?) = o?.let {
            LoginFlow(it.optString("state"), it.optStringOrNull("url"), it.optStringOrNull("code"), it.optBoolean("paste"), it.optStringOrNull("error"), it.optInt("expires_in"))
        }
    }
}

/** An agent's sign-in account, and the profile it runs on (null: its sign-in account). */
data class AgentLogin(
    val engine: String,
    val name: String,
    val loggedIn: Boolean?,
    val account: String?,
    val error: String?,
    val profile: String?,
    val profileChoices: List<String>,
    val flow: LoginFlow?,
) {
    companion object {
        fun from(o: JSONObject) = AgentLogin(
            engine = o.optString("engine"),
            name = o.optString("name"),
            loggedIn = if (o.isNull("logged_in") || !o.has("logged_in")) null else o.optBoolean("logged_in"),
            account = o.optStringOrNull("account"),
            error = o.optStringOrNull("error"),
            profile = o.optStringOrNull("profile"),
            profileChoices = o.optJSONArray("profile_choices").strings(),
            flow = LoginFlow.from(o.optJSONObject("flow")),
        )
    }
}

data class ServerState(val lastSeq: Long, val engines: List<EngineInfo>, val workdir: String)

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name) || !has(name)) null else optString(name).takeIf { it.isNotEmpty() }

fun JSONObject.optLongOrNull(name: String): Long? = if (isNull(name) || !has(name)) null else optLong(name)

/** A sign-in request from an agent: the owner fills it on the phone; the agent never sees the values. */
data class SecretAsk(
    val id: String,
    val sessionId: String,
    val site: String,
    /** The page's origin ("https://www.example.com"), which the sheet hands to password managers. */
    val origin: String,
    val reason: String,
    val fields: List<SecretField>,
    val status: String,
    val error: String?,
) {
    val pending: Boolean get() = status == "pending"

    companion object {
        fun from(o: JSONObject) = SecretAsk(
            id = o.optString("id"),
            sessionId = o.optString("session_id"),
            site = o.optString("site"),
            origin = o.optString("origin"),
            reason = o.optString("reason"),
            fields = o.optJSONArray("fields").objects().map { SecretField(it.optString("name"), it.optString("label"), it.optString("kind")) },
            status = o.optString("status"),
            error = o.optStringOrNull("error"),
        )
    }
}

data class SecretField(val name: String, val label: String, val kind: String)

/** One of the owner's phones (Settings > Security). */
data class Device(val id: String, val name: String, val createdAt: String, val lastSeen: String?, val current: Boolean, val key: String? = null) {
    companion object {
        fun from(o: JSONObject) = Device(o.optString("id"), o.optString("name"), o.optString("created_at"),
            o.optStringOrNull("last_seen"), o.optBoolean("current"), o.optStringOrNull("key"))
    }
}

/** One of the owner's keys: the FIDO security key ("u2f") or a phone passkey ("passkey"). */
data class OwnerKey(val id: String, val kind: String, val name: String, val keyHandle: String?, val credentialId: String?) {
    val passkey: Boolean get() = kind == "passkey"

    companion object {
        fun from(o: JSONObject) = OwnerKey(o.optString("id"), o.optString("kind"), o.optString("name"),
            o.optStringOrNull("key_handle"), o.optStringOrNull("credential_id"))
    }
}

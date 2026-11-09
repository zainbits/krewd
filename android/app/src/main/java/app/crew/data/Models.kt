package app.crew.data

import org.json.JSONArray
import org.json.JSONObject

data class ModeInfo(val id: String, val name: String, val description: String)

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
) {
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
            )
        }
    }
}

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
)

data class ServerState(val lastSeq: Long, val engines: List<EngineInfo>, val workdir: String)

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name) || !has(name)) null else optString(name).takeIf { it.isNotEmpty() }

fun JSONObject.optLongOrNull(name: String): Long? = if (isNull(name) || !has(name)) null else optLong(name)

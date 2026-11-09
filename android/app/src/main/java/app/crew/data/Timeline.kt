package app.crew.data

/** One row in a task's chat. Keys are stable so the list keeps scroll position as rows grow. */
sealed interface ChatItem {
    val key: String
}

/**
 * A message the owner sent. [state]: queued (stored on the server: one tick), delivered (the agent has it:
 * two ticks; [how] is "steered" or "started"), cancelled (dropped by Stop). Local rows not yet stored use
 * sending and failed.
 */
data class UserItem(
    override val key: String,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val tabs: List<BrowserTab> = emptyList(),
    val messageId: String? = null,
    val clientId: String? = null,
    val delivery: String? = null,
    val state: String? = null,
    val how: String? = null,
    /** Set when Crew sent it, not the owner: "routine" (a run of a routine) or "compact". */
    val purpose: String? = null,
    /** For a routine run: its title, trigger (schedule or manual) and instructions version. */
    val routineTitle: String? = null,
    val trigger: String? = null,
    val version: Int? = null,
    /** For a routine run: the triggers that fired and the attempt (2 of 3 for a follow-up). */
    val triggers: List<String> = emptyList(),
    val attempt: Int = 1,
    val maxAttempts: Int = 1,
    /** For a switch: the agent (engine id) the chat moves to. */
    val switchTo: String? = null,
) : ChatItem

/** The chat moved to another agent; [summary] is the handover the previous agent wrote. */
data class SwitchItem(override val key: String, val fromName: String, val toName: String, val summary: String?) : ChatItem

data class TextItem(override val key: String, val role: String, val text: String) : ChatItem {
    val isThought: Boolean get() = role == "thought"
}

data class ToolItem(
    override val key: String,
    val title: String,
    val kind: String?,
    val status: String?,
    val input: String?,
    val output: String?,
    /** Images in the tool result (a screenshot the agent read), as names for GET /api/tool-images. */
    val images: List<String> = emptyList(),
) : ChatItem

data class PlanEntry(val content: String, val status: String)

data class PlanItem(override val key: String, val entries: List<PlanEntry>) : ChatItem

data class ApprovalItem(override val key: String, val approval: Approval) : ChatItem

/** Files the agent handed back in its reply (they live in the server's shared folders). */
data class FilesItem(override val key: String, val files: List<Attachment>) : ChatItem

data class NoticeItem(override val key: String, val text: String, val isError: Boolean) : ChatItem

/**
 * Folds the server's durable events into chat rows. Text events append to the row with the same
 * message id; tool, plan and approval events replace their row, so replaying the whole log and
 * applying live events give the same result.
 */
class Timeline {
    private val items = ArrayList<ChatItem>()
    private val index = HashMap<String, Int>()
    private val messages = HashMap<String, String>() // message id -> row key
    /** Client ids the server has stored, so local "sending" rows can be dropped. */
    val clientIds = HashSet<String>()
    var lastSeq: Long = 0
        private set

    fun snapshot(): List<ChatItem> = items.toList()

    fun apply(event: Event): Boolean {
        if (event.seq <= lastSeq) return false
        lastSeq = event.seq
        val p = event.payload
        when (event.kind) {
            "user" -> {
                val messageId = p.optStringOrNull("message_id")
                val item = UserItem(
                    key = "user-${event.seq}",
                    text = p.optString("text"),
                    attachments = Attachment.list(p.optJSONArray("attachments")),
                    tabs = p.optJSONArray("tabs").objects().map { BrowserTab(it.optString("id"), it.optString("title"), it.optString("url")) },
                    messageId = messageId,
                    clientId = p.optStringOrNull("client_id"),
                    delivery = p.optStringOrNull("delivery"),
                    // Messages from before delivery tracking: treat as delivered.
                    state = if (messageId == null) "delivered" else "queued",
                    purpose = p.optStringOrNull("purpose"),
                    routineTitle = p.optJSONObject("meta")?.optStringOrNull("title"),
                    trigger = p.optJSONObject("meta")?.optStringOrNull("trigger"),
                    version = p.optJSONObject("meta")?.optInt("version"),
                    triggers = p.optJSONObject("meta")?.optJSONArray("triggers")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                    attempt = p.optJSONObject("meta")?.optInt("attempt", 1) ?: 1,
                    maxAttempts = p.optJSONObject("meta")?.optInt("max_attempts", 1) ?: 1,
                    switchTo = p.optJSONObject("meta")?.optStringOrNull("to"),
                )
                add(item)
                if (messageId != null) messages[messageId] = item.key
                item.clientId?.let { clientIds.add(it) }
            }
            "message" -> {
                val key = messages[p.optString("id")] ?: return false
                val item = items[index[key] ?: return false] as? UserItem ?: return false
                put(key, item.copy(state = p.optString("state"), how = p.optStringOrNull("delivered_how")))
            }
            "text" -> {
                val key = "text-" + p.optString("message_id", event.seq.toString())
                val existing = index[key]?.let { items[it] as? TextItem }
                if (existing != null) {
                    put(key, existing.copy(text = existing.text + p.optString("text")))
                } else {
                    add(TextItem(key, p.optString("role", "assistant"), p.optString("text")))
                }
            }
            "tool" -> {
                val key = "tool-" + p.optString("tool_call_id", event.seq.toString())
                put(
                    key,
                    ToolItem(
                        key = key,
                        title = p.optString("title", "Tool"),
                        kind = p.optStringOrNull("kind"),
                        status = p.optStringOrNull("status"),
                        input = p.optStringOrNull("input"),
                        output = p.optStringOrNull("output"),
                        images = p.optJSONArray("images").strings(),
                    ),
                )
            }
            "plan" -> {
                val entries = p.optJSONArray("entries").objects().map {
                    PlanEntry(it.optString("content"), it.optString("status", "pending"))
                }
                // One live plan per turn: later updates replace it in place.
                val key = index.keys.firstOrNull { it.startsWith("plan-open") } ?: "plan-open-${event.seq}"
                put(key, PlanItem(key, entries))
            }
            "approval" -> {
                val approval = Approval.from(p)
                put("approval-${approval.id}", ApprovalItem("approval-${approval.id}", approval))
            }
            "error" -> add(NoticeItem("error-${event.seq}", p.optString("message", "Something went wrong"), isError = true))
            "switch" -> add(
                SwitchItem(
                    "switch-${event.seq}",
                    p.optString("from_name", p.optString("from")),
                    p.optString("to_name", p.optString("to")),
                    p.optStringOrNull("summary"),
                ),
            )
            "compact" -> add(
                when {
                    !p.optBoolean("ok") -> NoticeItem("compact-${event.seq}", "Compacting did not finish; the context is unchanged", isError = true)
                    p.optString("how") == "native" -> NoticeItem("compact-${event.seq}", "Context compacted by the agent", isError = false)
                    else -> NoticeItem("compact-${event.seq}", "Context compacted: a fresh agent session continues from the handover above", isError = false)
                },
            )
            "turn_end" -> {
                closePlan()
                Attachment.list(p.optJSONArray("files")).takeIf { it.isNotEmpty() }?.let { add(FilesItem("files-${event.seq}", it)) }
                when (p.optString("stop_reason")) {
                    "cancelled" -> add(NoticeItem("end-${event.seq}", "Stopped", isError = false))
                    "max_tokens", "max_turn_requests" -> add(NoticeItem("end-${event.seq}", "Stopped at the agent's limit", isError = true))
                    "refusal" -> add(NoticeItem("end-${event.seq}", "The agent declined this request", isError = true))
                }
            }
            else -> return false
        }
        return true
    }

    private fun closePlan() {
        val key = index.keys.firstOrNull { it.startsWith("plan-open") } ?: return
        val at = index.remove(key) ?: return
        val plan = items[at] as PlanItem
        val closedKey = key.replace("plan-open", "plan")
        items[at] = plan.copy(key = closedKey)
        index[closedKey] = at
    }

    private fun add(item: ChatItem) {
        index[item.key] = items.size
        items.add(item)
    }

    private fun put(key: String, item: ChatItem) {
        val at = index[key]
        if (at == null) add(item) else items[at] = item
    }
}

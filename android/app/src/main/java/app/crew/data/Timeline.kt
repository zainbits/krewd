package app.crew.data

/** One row in a task's chat. Keys are stable so the list keeps scroll position as rows grow. */
sealed interface ChatItem {
    val key: String
}

data class UserItem(override val key: String, val text: String, val attachments: List<Attachment> = emptyList()) : ChatItem

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
) : ChatItem

data class PlanEntry(val content: String, val status: String)

data class PlanItem(override val key: String, val entries: List<PlanEntry>) : ChatItem

data class ApprovalItem(override val key: String, val approval: Approval) : ChatItem

data class NoticeItem(override val key: String, val text: String, val isError: Boolean) : ChatItem

/**
 * Folds the server's durable events into chat rows. Text events append to the row with the same
 * message id; tool, plan and approval events replace their row, so replaying the whole log and
 * applying live events give the same result.
 */
class Timeline {
    private val items = ArrayList<ChatItem>()
    private val index = HashMap<String, Int>()
    var lastSeq: Long = 0
        private set

    fun snapshot(): List<ChatItem> = items.toList()

    fun apply(event: Event): Boolean {
        if (event.seq <= lastSeq) return false
        lastSeq = event.seq
        val p = event.payload
        when (event.kind) {
            "user" -> add(UserItem("user-${event.seq}", p.optString("text"), Attachment.list(p.optJSONArray("attachments"))))
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
            "turn_end" -> {
                closePlan()
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

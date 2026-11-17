package app.krewd.data

import org.json.JSONObject
import java.util.Locale

/** What a chat takes on the server (see the server's storage.py and `GET /api/storage`). */
data class ChatStorage(
    val id: String,
    val title: String,
    val status: String,
    val pinned: Boolean,
    val archived: Boolean,
    val isRoutine: Boolean,
    val bytes: Long,
    val historyBytes: Long,
    val repeatsBytes: Long,
    val attachmentsBytes: Long,
    val attachments: Int,
    val imagesBytes: Long,
    val images: Int,
    val transcriptBytes: Long,
) {
    val isBusy: Boolean get() = status == "running" || status == "waiting"
    /** What Trim can free: the repeats, most of the tool output and the tool images. */
    val trimmable: Boolean get() = repeatsBytes > 0 || imagesBytes > 0 || historyBytes > 256 * 1024
}

data class StorageReport(
    val databaseBytes: Long,
    val totalBytes: Long,
    val chats: List<ChatStorage>,
    val repeatsBytes: Long,
    val looseFilesBytes: Long,
    val looseFiles: Int,
    val looseTranscriptsBytes: Long,
    val looseTranscripts: Int,
) {
    val reclaimableBytes: Long get() = repeatsBytes + looseFilesBytes + looseTranscriptsBytes

    companion object {
        fun from(o: JSONObject): StorageReport {
            val r = o.optJSONObject("reclaimable") ?: JSONObject()
            val files = r.optJSONObject("loose_files") ?: JSONObject()
            val transcripts = r.optJSONObject("loose_transcripts") ?: JSONObject()
            val chats = o.optJSONArray("chats")
            return StorageReport(
                databaseBytes = o.optLong("database_bytes"),
                totalBytes = o.optLong("total_bytes"),
                chats = (0 until (chats?.length() ?: 0)).map { i ->
                    val c = chats!!.getJSONObject(i)
                    val history = c.optJSONObject("history") ?: JSONObject()
                    val att = c.optJSONObject("attachments") ?: JSONObject()
                    val img = c.optJSONObject("tool_images") ?: JSONObject()
                    ChatStorage(
                        id = c.getString("id"),
                        title = c.optString("title").ifBlank { "Untitled" },
                        status = c.optString("status"),
                        pinned = c.optBoolean("pinned"),
                        archived = c.optBoolean("archived"),
                        isRoutine = !c.isNull("routine_id"),
                        bytes = c.optLong("bytes"),
                        historyBytes = history.optLong("bytes"),
                        repeatsBytes = history.optLong("repeats_bytes"),
                        attachmentsBytes = att.optLong("bytes"),
                        attachments = att.optInt("count"),
                        imagesBytes = img.optLong("bytes"),
                        images = img.optInt("count"),
                        transcriptBytes = (c.optJSONObject("transcript") ?: JSONObject()).optLong("bytes"),
                    )
                },
                repeatsBytes = r.optLong("repeats_bytes"),
                looseFilesBytes = files.optLong("bytes"),
                looseFiles = files.optInt("count"),
                looseTranscriptsBytes = transcripts.optLong("bytes"),
                looseTranscripts = transcripts.optInt("count"),
            )
        }
    }
}

fun KrewdApi.storage(): StorageReport = StorageReport.from(JSONObject(rawCall("GET", "/api/storage")))

/** Frees what no chat needs; the number of bytes freed. */
fun KrewdApi.cleanStorage(): Long = JSONObject(rawCall("POST", "/api/storage/clean", JSONObject())).optLong("bytes")

/** Shrinks one chat's history in Krewd, keeping its messages and the agent's session; the bytes freed. */
fun KrewdApi.trimSession(id: String): Long = JSONObject(rawCall("POST", "/api/sessions/$id/trim", JSONObject())).optLong("bytes")

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
    else -> "$bytes B"
}

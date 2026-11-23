package app.krewd.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * A message on its way to the server: kept on the phone (see [OutboxStore]) until the server has stored it, and
 * shown in the chat until its own row arrives. [sent]: the server has it; [failed]: the server refused it (or one
 * of its files), so it waits for the owner to resend.
 */
data class OutgoingMessage(
    val clientId: String,
    val sessionId: String,
    val text: String,
    val files: List<PendingFile>,
    val tabs: List<BrowserTab>,
    val delivery: String?,
    val failed: Boolean = false,
    val chats: List<ChatRef> = emptyList(),
    val notes: List<ReplyNote> = emptyList(),
    val sent: Boolean = false,
) {
    val ready: Boolean get() = files.all { it.uploadId != null }
}

/**
 * Unsent messages and the files picked for them, on the phone, so a dropped connection or a closed app loses
 * nothing. Picked files are copied into the app's own storage at once ([copyIn]): the picker's permission to the
 * original ends with the app, and a retry must still be able to read the file. Copies are deleted once the server
 * has the message, or the file is removed from the draft ([delete], [clean]).
 */
class OutboxStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("krewd_outbox", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "outgoing")

    fun messages(): List<OutgoingMessage> = runCatching {
        JSONArray(prefs.getString("messages", "[]")).objects().map(::messageFrom)
    }.getOrDefault(emptyList())

    /** Commits at once: this is the copy that must survive the app being closed right after Send. */
    fun saveMessages(list: List<OutgoingMessage>) {
        prefs.edit().putString("messages", JSONArray(list.map(::messageJson)).toString()).commit()
    }

    fun draftKeys(): List<String> = prefs.all.keys.filter { it.startsWith("files:") }.map { it.removePrefix("files:") }

    fun draft(key: String): List<PendingFile> = runCatching {
        JSONArray(prefs.getString("files:$key", "[]")).objects().map(::fileFrom)
    }.getOrDefault(emptyList())

    fun saveDraft(key: String, files: List<PendingFile>) {
        prefs.edit().apply {
            if (files.isEmpty()) remove("files:$key") else putString("files:$key", JSONArray(files.map(::fileJson)).toString())
        }.apply()
    }

    /** Copies a picked file into the app's storage; blocking, call off the main thread. */
    fun copyIn(resolver: ContentResolver, uri: Uri, localId: Long, name: String): File {
        dir.mkdirs()
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifBlank { "file" }
        val file = File(dir, "$localId-$safe")
        val input = resolver.openInputStream(uri) ?: throw java.io.IOException("Cannot read $name")
        input.use { i -> file.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        return file
    }

    /** A content address for a copy, for previews and sharing (FileProvider, as for shared images). */
    fun uriOf(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.images", file)

    fun delete(files: List<PendingFile>) = files.forEach { f -> f.path?.let { File(it).delete() } }

    /** Deletes copies that nothing refers to any more (an app closed between a send and its cleanup). */
    fun clean(keep: Set<String>) {
        dir.listFiles()?.forEach { if (it.path !in keep) it.delete() }
    }

    private fun messageJson(m: OutgoingMessage) = JSONObject()
        .put("client_id", m.clientId).put("session_id", m.sessionId).put("text", m.text)
        .put("files", JSONArray(m.files.map(::fileJson)))
        .put("tabs", JSONArray(m.tabs.map { JSONObject().put("id", it.id).put("title", it.title).put("url", it.url) }))
        .put("delivery", m.delivery ?: JSONObject.NULL).put("failed", m.failed)
        .put("chats", JSONArray(m.chats.map { it.toJson() })).put("notes", JSONArray(m.notes.map { it.toJson() }))

    private fun messageFrom(o: JSONObject) = OutgoingMessage(
        clientId = o.getString("client_id"),
        sessionId = o.getString("session_id"),
        text = o.optString("text"),
        files = o.optJSONArray("files").objects().map(::fileFrom),
        tabs = o.optJSONArray("tabs").objects().map { BrowserTab(it.optString("id"), it.optString("title"), it.optString("url")) },
        delivery = o.optStringOrNull("delivery"),
        failed = o.optBoolean("failed"),
        chats = ChatRef.list(o.optJSONArray("chats")),
        notes = ReplyNote.list(o.optJSONArray("notes")),
    )

    private fun fileJson(f: PendingFile) = JSONObject().put("local_id", f.localId).put("name", f.name)
        .put("size", f.size ?: JSONObject.NULL).put("upload_id", f.uploadId ?: JSONObject.NULL)
        .put("error", f.error ?: JSONObject.NULL).put("uri", f.uri ?: JSONObject.NULL)
        .put("mime", f.mime ?: JSONObject.NULL).put("path", f.path ?: JSONObject.NULL)

    private fun fileFrom(o: JSONObject) = PendingFile(
        localId = o.getLong("local_id"),
        name = o.optString("name").ifBlank { "file" },
        size = if (o.isNull("size")) null else o.optLong("size"),
        uploadId = o.optStringOrNull("upload_id"),
        error = o.optStringOrNull("error"),
        uri = o.optStringOrNull("uri"),
        mime = o.optStringOrNull("mime"),
        path = o.optStringOrNull("path"),
    )
}

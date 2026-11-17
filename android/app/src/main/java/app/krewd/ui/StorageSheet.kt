package app.krewd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.krewd.KrewdViewModel
import app.krewd.data.ChatStorage
import app.krewd.data.StorageReport
import app.krewd.data.cleanStorage
import app.krewd.data.formatBytes
import app.krewd.data.storage
import app.krewd.data.trimSession

private const val SHOWN = 15

/**
 * Settings > Storage: what each chat takes on the server, largest first. "Free up space" removes only what no
 * chat needs; Trim shrinks one chat in Krewd while its agent session stays; Delete removes the chat for good.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSheet(viewModel: KrewdViewModel, onDismiss: () -> Unit) {
    var report by remember { mutableStateOf<StorageReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<ChatStorage?>(null) }
    var showAll by remember { mutableStateOf(false) }

    fun load() {
        busy = true
        viewModel.request({ it.storage() }) { result, problem ->
            busy = false
            if (result != null) { report = result; error = null } else error = problem
        }
    }
    fun freed(what: String): (Long?, String?) -> Unit = { bytes, problem ->
        if (bytes != null) { note = "$what: ${formatBytes(bytes)} freed"; load() } else { busy = false; error = problem }
    }
    LaunchedEffect(Unit) { load() }

    confirmDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${chat.title}”?") },
            text = {
                Text(
                    "Frees ${formatBytes(chat.bytes)}: the chat, its attachments and images, and the agent's record of it. " +
                        "This cannot be undone." + if (chat.isRoutine) " The routine stays; its next run starts a new chat." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    busy = true
                    viewModel.deleteSessionNow(chat.id) { problem ->
                        if (problem == null) { note = "Deleted “${chat.title}”: ${formatBytes(chat.bytes)} freed"; load() }
                        else { busy = false; error = problem }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Storage", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            note?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            val r = report
            if (r == null) {
                Text(if (busy) "Measuring…" else "Not loaded", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            SectionCard {
                Text("Chats take ${formatBytes(r.totalBytes)}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "History in the database (${formatBytes(r.databaseBytes)} in all), attachments, tool images and each agent's own record of the chat. All of it is on the server; nothing is kept on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionCard {
                Text("Free up space safely", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(
                        "${formatBytes(r.repeatsBytes)} repeated history rows",
                        "${formatBytes(r.looseFilesBytes)} in ${r.looseFiles} files of deleted chats",
                        "${formatBytes(r.looseTranscriptsBytes)} in ${r.looseTranscripts} old agent sessions no chat uses",
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Every chat stays as it is, and its agent picks up where it left off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { busy = true; note = null; viewModel.request({ it.cleanStorage() }, freed("Cleaned up")) },
                    enabled = !busy && r.reclaimableBytes > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (r.reclaimableBytes > 0) "Free ${formatBytes(r.reclaimableBytes)}" else "Nothing to free") }
            }
            Text("Largest chats", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            Text(
                "Trim keeps the messages, replies and attachments and the agent's session, and shortens tool details and removes tool images. Delete removes the chat for good.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val chats = if (showAll) r.chats else r.chats.take(SHOWN)
            chats.forEach { chat ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(chat.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text(formatBytes(chat.bytes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(
                        listOfNotNull(
                            "history ${formatBytes(chat.historyBytes)}",
                            "agent ${formatBytes(chat.transcriptBytes)}".takeIf { chat.transcriptBytes > 0 },
                            "${chat.images} images ${formatBytes(chat.imagesBytes)}".takeIf { chat.images > 0 },
                            "${chat.attachments} attached ${formatBytes(chat.attachmentsBytes)}".takeIf { chat.attachments > 0 },
                        ).joinToString(" · ") + listOfNotNull(
                            "working".takeIf { chat.isBusy }, "routine".takeIf { chat.isRoutine },
                            "pinned".takeIf { chat.pinned }, "archived".takeIf { chat.archived },
                        ).joinToString("") { " · $it" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { busy = true; note = null; viewModel.request({ it.trimSession(chat.id) }, freed("Trimmed “${chat.title}”")) },
                            enabled = !busy && !chat.isBusy && chat.trimmable,
                            modifier = Modifier.weight(1f),
                        ) { Text("Trim") }
                        OutlinedButton(
                            onClick = { note = null; confirmDelete = chat },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Delete") }
                    }
                }
            }
            if (!showAll && r.chats.size > SHOWN) {
                TextButton(onClick = { showAll = true }, modifier = Modifier.fillMaxWidth()) { Text("Show all ${r.chats.size} chats") }
            }
        }
    }
}

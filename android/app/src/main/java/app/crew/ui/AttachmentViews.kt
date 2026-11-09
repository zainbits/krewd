package app.crew.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.crew.R
import app.crew.data.Attachment
import app.crew.data.PendingFile
import app.crew.data.humanSize

/** Opens the system file picker (any type, several files); call the returned function to show it. */
@Composable
fun rememberFilePicker(onPicked: (List<Uri>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    return { launcher.launch(arrayOf("*/*")) }
}

/** Files picked for the next message, with upload state; tap one to remove it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PendingFiles(files: List<PendingFile>, onRemove: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (files.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { f ->
            InputChip(
                selected = false,
                onClick = { onRemove(f.localId) },
                label = {
                    Text(
                        if (f.error != null) "${f.name}: ${f.error}" else listOf(f.name, humanSize(f.size)).filter { it.isNotEmpty() }.joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (f.error != null) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                },
                leadingIcon = {
                    if (f.uploading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(painterResource(R.drawable.ic_attach_24), contentDescription = null, Modifier.size(InputChipDefaults.IconSize))
                },
                trailingIcon = { Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Remove", Modifier.size(InputChipDefaults.IconSize)) },
            )
        }
    }
}

/** Files sent with a message: name, size and where the server saved them. */
@Composable
fun SentFiles(files: List<Attachment>, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        files.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_attach_24), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Text("${f.name} · ${humanSize(f.size)}", color = color, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

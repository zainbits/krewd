package app.krewd.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.krewd.R
import app.krewd.data.Attachment
import app.krewd.data.ImageRef
import app.krewd.data.PendingFile
import app.krewd.data.humanSize

/** Opens the system file picker (any type, several files); call the returned function to show it. */
@Composable
fun rememberFilePicker(onPicked: (List<Uri>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    return { launcher.launch(arrayOf("*/*")) }
}

/**
 * Files picked for the next message, as a row of tiles at the top of the composer: pictures as thumbnails
 * (tap one to see it full screen), other files as a card with their type and size. The x removes a file.
 */
@Composable
fun AttachmentTray(files: List<PendingFile>, onRemove: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (files.isEmpty()) return
    val viewer = LocalImageViewer.current
    val pictures = files.filter { it.isImage }
    val refs = pictures.map { ImageRef.Local(it.uri!!, it.name, it.mime) }
    LazyRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        items(files, key = { it.localId }) { f ->
            val index = pictures.indexOf(f)
            PendingTile(
                file = f,
                picture = refs.getOrNull(index),
                onOpen = { if (index >= 0) viewer?.open?.invoke(refs, index) },
                onRemove = { onRemove(f.localId) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

private val TILE = 76.dp

@Composable
private fun PendingTile(file: PendingFile, picture: ImageRef?, onOpen: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.size(TILE + 6.dp)) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .size(TILE)
                .clip(shape)
                .background(colors.surfaceContainerHighest)
                .then(if (file.error != null) Modifier.border(1.5.dp, colors.error, shape) else Modifier)
                .clickable(enabled = picture != null, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            if (picture != null) {
                Thumbnail(picture, Modifier.fillMaxSize())
            } else {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        file.name.substringAfterLast('.', "file").take(5).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                    )
                    Text(file.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
                }
            }
            if (file.uploading) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                }
            } else if (file.error != null) {
                Box(Modifier.fillMaxSize().background(colors.errorContainer.copy(alpha = 0.85f)).padding(4.dp), contentAlignment = Alignment.Center) {
                    Text(file.error, style = MaterialTheme.typography.labelSmall, color = colors.onErrorContainer, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Surface(
            onClick = onRemove,
            shape = CircleShape,
            color = colors.inverseSurface,
            contentColor = colors.inverseOnSurface,
            modifier = Modifier.align(Alignment.TopEnd).size(22.dp),
        ) {
            Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Remove ${file.name}", modifier = Modifier.padding(4.dp))
        }
    }
}

/** A picture cropped to fill its box, with a spinner while it loads. */
@Composable
fun Thumbnail(ref: ImageRef, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (val load = rememberImage(ref)) {
            is ImageLoad.Ready -> Image(load.bitmap, contentDescription = ref.name, contentScale = contentScale, modifier = Modifier.matchParentSize())
            is ImageLoad.Loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            is ImageLoad.Failed -> Icon(painterResource(R.drawable.ic_attach_24), contentDescription = load.reason, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Files sent with a message: pictures as thumbnails that open full screen, other files by name and size. */
@Composable
fun SentFiles(files: List<Attachment>, color: Color) {
    val viewer = LocalImageViewer.current
    val pictures = files.filter { it.isImage }
    val refs = pictures.map { ImageRef.Sent(it) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (pictures.isNotEmpty()) {
            val shape = RoundedCornerShape(12.dp)
            if (pictures.size == 1) {
                Thumbnail(
                    refs[0],
                    Modifier.width(240.dp).heightIn(max = 280.dp).aspectRatio(4f / 3f).clip(shape)
                        .background(Color(0x22000000)).clickable { viewer?.open?.invoke(refs, 0) },
                )
            } else {
                refs.chunked(2).forEachIndexed { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        pair.forEachIndexed { col, ref ->
                            Thumbnail(
                                ref,
                                Modifier.size(118.dp).clip(shape).background(Color(0x22000000))
                                    .clickable { viewer?.open?.invoke(refs, row * 2 + col) },
                            )
                        }
                    }
                }
            }
        }
        files.filterNot { it.isImage }.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_attach_24), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Text("${f.name} · ${humanSize(f.size)}", color = color, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Files the agent handed back: tap one to save it to Downloads/Krewd and open it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HandedFiles(files: List<Attachment>, downloading: Set<String>, onOpen: (Attachment) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { f ->
            SuggestionChip(
                onClick = { onOpen(f) },
                label = { Text("${f.name} · ${humanSize(f.size)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = {
                    if (f.path in downloading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(painterResource(R.drawable.ic_download_24), contentDescription = "Download", Modifier.size(SuggestionChipDefaults.IconSize))
                },
            )
        }
    }
}

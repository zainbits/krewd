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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.getValue
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
import app.krewd.LocalKrewdColors
import app.krewd.data.Attachment
import app.krewd.data.FileLoad
import app.krewd.data.ImageRef
import app.krewd.data.PendingFile
import app.krewd.data.humanSize
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.zainbits.cohesion.GlassBackdrop
import io.github.zainbits.vitreous.GlassShape
import io.github.zainbits.vitreous.GlassSurface
import io.github.zainbits.cohesion.LocalGlassStyle

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

/**
 * Shows a handed-back file's full name in a small card by the chip (its bounds in the window). The chat screen
 * provides it, so the card can be glass over the chat.
 */
val LocalFilePeek = compositionLocalOf<((Attachment, Rect) -> Unit)?> { null }

/**
 * Files the agent handed back: tap one to save it to Downloads/Krewd and open it. While it downloads it can be
 * paused (resumed later from where it stopped) or cancelled; a long press shows the full name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HandedFiles(
    files: List<Attachment>,
    loads: Map<String, FileLoad>,
    onOpen: (Attachment) -> Unit,
    onPause: (Attachment) -> Unit,
    onCancel: (Attachment) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { f -> HandedFile(f, loads[f.downloadKey], onOpen = { onOpen(f) }, onPause = { onPause(f) }, onCancel = { onCancel(f) }) }
    }
}

/**
 * One handed-back file: a plain pill (no glass or stretch: it sits inside the chat the glass shows) with the
 * name, shortened in the middle so the extension stays, and the size, which always shows. While it downloads a
 * shade fills it from the left, with pause and cancel buttons; paused, a resume button. Saved: a green tick (a
 * tap opens it). Broken off: a red stop sign (a tap resumes it, the cross discards it).
 */
@Composable
private fun HandedFile(file: Attachment, load: FileLoad?, onOpen: () -> Unit, onPause: () -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val progress = when (load) {
        is FileLoad.Running -> load.progress
        is FileLoad.Paused -> load.progress
        else -> null
    }
    val shown by androidx.compose.animation.core.animateFloatAsState(progress ?: 0f, label = "download")
    val shade = colors.primary.copy(alpha = if (load is FileLoad.Paused) 0.14f else 0.24f)
    val peek = LocalFilePeek.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    // Downloading: the chip itself does nothing (its buttons pause and cancel). Otherwise download, resume or open.
    val tap: (() -> Unit)? = if (load is FileLoad.Running) null else onOpen
    val longTap: (() -> Unit)? = peek?.let { show -> { show(file, bounds) } }
    val body: @Composable () -> Unit = {
        Row(
            Modifier
                .drawBehind { if (progress != null) drawRect(shade, size = size.copy(width = size.width * shown)) }
                .heightIn(min = 36.dp)
                .padding(start = 2.dp, end = if (load == null || load == FileLoad.Done) 14.dp else 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (load) {
                is FileLoad.Running -> ChipButton(R.drawable.ic_pause_24, "Pause download", colors.primary, onPause)
                is FileLoad.Paused -> ChipButton(R.drawable.ic_play_24, "Resume download", colors.primary, onOpen)
                else -> Icon(
                    painterResource(
                        when (load) {
                            FileLoad.Done -> R.drawable.ic_check_24
                            FileLoad.Failed -> R.drawable.ic_report_24
                            else -> R.drawable.ic_download_24
                        },
                    ),
                    contentDescription = when (load) {
                        FileLoad.Done -> "Downloaded"
                        FileLoad.Failed -> "Download failed, tap to resume"
                        else -> "Download"
                    },
                    tint = when (load) {
                        FileLoad.Done -> LocalKrewdColors.current.ok
                        FileLoad.Failed -> colors.error
                        else -> colors.primary
                    },
                    modifier = Modifier.padding(start = 10.dp, end = 8.dp).size(18.dp),
                )
            }
            Text(
                file.name,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                " · " + humanSize(file.size),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            if (load is FileLoad.Running || load is FileLoad.Paused || load == FileLoad.Failed) {
                ChipButton(
                    R.drawable.ic_close_24,
                    if (load == FileLoad.Failed) "Discard the partial download" else "Cancel download",
                    colors.onSurfaceVariant, onCancel,
                )
            }
        }
    }
    val chip = Modifier
        .widthIn(max = 300.dp)
        .onGloballyPositioned { bounds = it.boundsInWindow() }
    Surface(
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = chip,
    ) {
        Box(Modifier.combinedClickable(enabled = tap != null || longTap != null, onLongClick = longTap, onClick = tap ?: {})) { body() }
    }
}

/** A small round button inside a file chip (pause, resume, cancel), with a full-size touch target. */
@Composable
private fun ChipButton(@androidx.annotation.DrawableRes icon: Int, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** The full name and size of a handed-back file, in a small card by its chip; tap anywhere to close. */
@Composable
fun FilePeekCard(file: Attachment, chip: Rect, backdrop: GlassBackdrop?, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
    ) {
        val body: @Composable () -> Unit = {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    humanSize(file.size) + (file.mime.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        }
        val place = Modifier.layout { measurable, constraints ->
            val margin = with(density) { 12.dp.roundToPx() }
            val gap = with(density) { 6.dp.roundToPx() }
            val p = measurable.measure(
                constraints.copy(minWidth = 0, minHeight = 0, maxWidth = minOf(constraints.maxWidth - 2 * margin, with(density) { 320.dp.roundToPx() })),
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                val x = (chip.left - origin.x).toInt().coerceIn(margin, maxOf(margin, constraints.maxWidth - p.width - margin))
                val above = (chip.top - origin.y).toInt() - gap - p.height
                val y = if (above >= margin * 6) above else (chip.bottom - origin.y).toInt() + gap
                p.place(x, y)
            }
        }
        if (backdrop != null) {
            // A touch more tint than the rest of the glass (+10, at most fully tinted), so the name reads over anything.
            val global = LocalGlassStyle.current
            val style = remember(global) { global.copy(tintAlpha = (global.tintAlpha + 0.10f).coerceAtMost(1f)) }
            GlassSurface(backdrop = backdrop, modifier = place, shape = GlassShape.rounded(16.dp), style = style) { body() }
        } else {
            Surface(modifier = place, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 3.dp) { body() }
        }
    }
}

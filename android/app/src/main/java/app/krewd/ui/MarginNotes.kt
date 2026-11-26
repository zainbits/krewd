/**
 * Margin notes: the owner's replies to single parts of an agent's message, like comments on a document.
 *
 * Every top-level part of a reply (a paragraph, a heading, one item of a list, a code block, a table, a quote) is a
 * [NoteTarget]: swipe it right to note it (Settings > Chat can turn the swipe off), or select words and pick Note in
 * the selection menu, next to its own Copy, to quote just those. A tap does nothing, so reading and selecting stay
 * as they were. Each note gets a
 * number and a color, shows on its part, and waits in the [NoteTray] above the composer with a field for what to
 * say about it. They go together with the next message, where [SentNotes] shows them as quote cards: tap one to jump
 * back to its part.
 *
 * A part that scrolls sideways (a code block, a table) registers its scroll state in [LocalNoteScrolls]: a swipe
 * right only notes it when it is already at its start, decided when the finger goes down. Otherwise the swipe
 * scrolls it, as before.
 */
package app.krewd.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.krewd.R
import app.krewd.data.ReplyNote
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Note colors by number (1 is the first), dark ink on each. */
private val NOTE_COLORS = listOf(
    Color(0xFFFF8A3D), Color(0xFF5AC8FA), Color(0xFFB48CFF), Color(0xFF4CD98A), Color(0xFFFF6B8B), Color(0xFFF2C744),
)
private val NOTE_INK = Color(0xFF16120E)

fun noteColor(number: Int): Color = NOTE_COLORS[(number - 1).mod(NOTE_COLORS.size)]

/** How far a part slides before letting go notes it, and how far it can go. */
private val SWIPE_NOTE = 64.dp
private val SWIPE_MAX = 96.dp
private const val FLASH_MS = 1_400

/** What the chat gives its replies' parts: notes on them, the tapped one, and what tapping and swiping do. */
@Stable
class NoteHooks(
    val swipe: Boolean,
    /** Note numbers (from 1) by the ref of the part they quote. */
    val marks: Map<String, List<Int>>,
    /** The part a tapped quote card jumped to, lit for a moment. */
    val flash: String?,
    /** A note on a whole part: its ref and its text. */
    val onNote: (ref: String, quote: String) -> Unit,
    /** A note on words selected in the reply with [itemKey] (its Markdown is [text]). */
    val onSelectionNote: (itemKey: String, text: String, quote: String) -> Unit,
)

val LocalNoteHooks = compositionLocalOf<NoteHooks?> { null }

/** The timeline key of the reply being drawn; parts outside a reply (handovers, previews) take no notes. */
val LocalNoteItem = staticCompositionLocalOf<String?> { null }

/** Sideways scrolls inside the part being drawn (code, tables): a swipe notes the part only when they are at their start. */
val LocalNoteScrolls = staticCompositionLocalOf<SnapshotStateList<ScrollState>?> { null }

/** Code blocks open wrapped or not: the last pick of any block's Wrap button, kept with the app's settings. */
class CodeWrap(val default: Boolean, val set: (Boolean) -> Unit)

val LocalCodeWrap = staticCompositionLocalOf { CodeWrap(false) {} }

/** Registers a part's sideways [scroll] with the part around it, for the swipe (see [LocalNoteScrolls]). */
@Composable
fun RegisterNoteScroll(scroll: ScrollState) {
    val scrolls = LocalNoteScrolls.current ?: return
    DisposableEffect(scroll, scrolls) {
        scrolls.add(scroll)
        onDispose { scrolls.remove(scroll) }
    }
}

/** One part of a reply that takes margin notes (see the file comment). Outside a reply it is just [content]. */
@Composable
fun NoteTarget(blockId: String, plain: () -> String, content: @Composable () -> Unit) {
    val hooks = LocalNoteHooks.current
    val item = LocalNoteItem.current
    if (hooks == null || item == null) {
        content()
        return
    }
    val ref = "$item#$blockId"
    val marks = hooks.marks[ref].orEmpty()
    val scrolls = remember { mutableStateListOf<ScrollState>() }
    val slide = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    val bring = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val notePx = with(density) { SWIPE_NOTE.toPx() }
    val maxPx = with(density) { SWIPE_MAX.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    val mark = marks.firstOrNull()?.let(::noteColor)
    val currentHooks by androidx.compose.runtime.rememberUpdatedState(hooks)
    val currentPlain by androidx.compose.runtime.rememberUpdatedState(plain)

    LaunchedEffect(hooks.flash == ref) {
        if (hooks.flash == ref) {
            bring.bringIntoView()
            flash.snapTo(1f)
            flash.animateTo(0f, tween(FLASH_MS))
        }
    }

    Box(Modifier.fillMaxWidth()) {
        if (slide.value > 1f) {
            val reached = (slide.value / notePx).coerceIn(0f, 1f)
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    // Rides just left of the sliding text: it comes in from off the screen's edge, never over the words.
                    .offset { IntOffset((slide.value - 44.dp.toPx()).roundToInt(), 0) }
                    .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                    .size(28.dp)
                    .background(accent.copy(alpha = 0.15f + 0.85f * (if (reached >= 1f) 1f else reached * 0.5f)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_reply_24),
                    contentDescription = null,
                    tint = if (reached >= 1f) MaterialTheme.colorScheme.onPrimary else accent,
                    // Stands upright, turns as the swipe goes on, and lands level at the note point.
                    modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 90f * (1f - reached) },
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bring)
                .offset { IntOffset(slide.value.roundToInt(), 0) }
                .then(
                    if (!hooks.swipe) Modifier
                    else Modifier.pointerInput(ref) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            // Code or a table scrolled sideways: this swipe scrolls it back first.
                            if (scrolls.any { it.value > 0 }) return@awaitEachGesture
                            val slop = viewConfiguration.touchSlop
                            val longPress = viewConfiguration.longPressTimeoutMillis
                            var total = Offset.Zero
                            var claimed = false
                            var armed = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (claimed) change.consume()
                                    break
                                }
                                if (!claimed) {
                                    // Someone else's gesture, a second finger, or a long press (selecting words).
                                    if (change.isConsumed || event.changes.size > 1) break
                                    if (change.uptimeMillis - down.uptimeMillis > longPress) break
                                    total += change.positionChange()
                                    if (total.getDistance() <= slop) continue
                                    if (total.x > 0 && abs(total.x) > abs(total.y) * 1.5f) claimed = true else break
                                } else {
                                    total += change.positionChange()
                                }
                                change.consume()
                                val x = ((total.x - slop) * 0.8f).coerceIn(0f, maxPx)
                                scope.launch { slide.snapTo(x) }
                                val nowArmed = x >= notePx
                                if (nowArmed != armed) {
                                    armed = nowArmed
                                    if (armed) {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        scope.launch {
                                            pop.animateTo(1.35f, tween(70))
                                            pop.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMediumLow))
                                        }
                                    }
                                }
                            }
                            if (claimed) {
                                if (armed) currentHooks.onNote(ref, currentPlain())
                                scope.launch { slide.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
                            }
                        }
                    },
                )
                .drawBehind {
                    // Drawn into the chat's side margin, so the text does not move.
                    val out = 8.dp.toPx()
                    val lit = when {
                        flash.value > 0f -> accent.copy(alpha = 0.28f * flash.value)
                        mark != null -> mark.copy(alpha = 0.08f)
                        else -> Color.Transparent
                    }
                    val topLeft = Offset(-out, -3.dp.toPx())
                    val card = Size(size.width + out + 4.dp.toPx(), size.height + 6.dp.toPx())
                    val radius = CornerRadius(10.dp.toPx())
                    if (lit != Color.Transparent) drawRoundRect(lit, topLeft, card, radius)
                    val bar = mark
                    if (bar != null) {
                        // The bar is the card's left edge, clipped to its rounded shape, so the corners cut it.
                        val edge = Path().apply { addRoundRect(RoundRect(Rect(topLeft, card), radius)) }
                        clipPath(edge) { drawRect(bar, topLeft, Size(3.dp.toPx(), card.height)) }
                    }
                },
        ) {
            CompositionLocalProvider(LocalNoteScrolls provides scrolls) { content() }
            if (marks.isNotEmpty()) {
                Column(Modifier.align(Alignment.TopStart).offset(x = (-16).dp, y = (-4).dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    marks.forEach { NoteBadge(it, small = true) }
                }
            }
        }
    }
}

/** A note's number in its color. */
@Composable
fun NoteBadge(number: Int, small: Boolean = false) {
    val size = if (small) 15.dp else 20.dp
    Box(Modifier.size(size).background(noteColor(number), CircleShape), contentAlignment = Alignment.Center) {
        Text(
            number.toString(),
            color = NOTE_INK,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = if (small) 9.sp else 11.sp, lineHeight = if (small) 9.sp else 11.sp),
        )
    }
}

private object NoteMenuKey

/**
 * Adds Note to the selection menu of a reply: it quotes the selected words. Compose does not hand out the selected
 * text, so Note runs the menu's own Copy and reads the clipboard back (the words are on the clipboard too).
 */
@Composable
fun Modifier.noteSelectionMenu(onQuote: (String) -> Unit): Modifier {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copy = remember { arrayOfNulls<TextContextMenuItem>(1) }
    val current by androidx.compose.runtime.rememberUpdatedState(onQuote)
    return this
        .filterTextContextMenuComponents { c ->
            if (c is TextContextMenuItem && c.key == TextContextMenuKeys.CopyKey) copy[0] = c
            true
        }
        .appendTextContextMenuComponents {
            item(NoteMenuKey, "Note") {
                val copyItem = copy[0]
                if (copyItem == null) {
                    close()
                    return@item
                }
                val clips = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                fun clip(): String? = runCatching { clips.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
                val before = clip()
                copyItem.onClick(this)
                scope.launch {
                    // Copy writes the clipboard a moment later; the same words as before arrive unchanged.
                    repeat(16) { tries ->
                        val now = clip()
                        if (!now.isNullOrBlank() && (now != before || tries >= 4)) {
                            current(now)
                            return@launch
                        }
                        delay(25)
                    }
                }
            }
        }
}

/** The notes waiting for the next message, above the composer: each with its quote (tap: show it) and a field. */
@Composable
fun NoteTray(
    notes: List<ReplyNote>,
    focus: Int?,
    onFocused: () -> Unit,
    onJump: (ReplyNote) -> Unit,
    onText: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notes.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = colors.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(app.krewd.constants.ShellDimensions.PILL_BORDER_WIDTH, colors.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (notes.size == 1) "1 note · goes with your message" else "${notes.size} notes · go with your message",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                Text(
                    "Clear",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClear).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Column(
                Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                notes.forEachIndexed { i, note ->
                    androidx.compose.runtime.key(note.ref, note.quote) {
                        NoteCard(
                            number = i + 1,
                            note = note,
                            focus = focus == i,
                            onFocused = onFocused,
                            onJump = { onJump(note) },
                            onText = { onText(i, it) },
                            onRemove = { onRemove(i) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(
    number: Int,
    note: ReplyNote,
    focus: Boolean,
    onFocused: () -> Unit,
    onJump: () -> Unit,
    onText: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tone = noteColor(number)
    var value by remember { mutableStateOf(TextFieldValue(note.note)) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(focus) {
        if (focus) {
            requester.requestFocus()
            onFocused()
        }
    }
    // The accent bar is part of the card: clipped to its rounded shape, so the corners cut it.
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainerHighest)
            .drawBehind { drawRect(tone, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .padding(start = 10.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.padding(top = 1.dp)) { NoteBadge(number) }
        Column(Modifier.weight(1f)) {
            Text(
                note.quote.replace(Regex("\\s+"), " "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onJump),
            )
            BasicTextField(
                value = value,
                onValueChange = {
                    if (it.text != value.text) onText(it.text)
                    value = it
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                maxLines = 5,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).focusRequester(requester),
                decorationBox = { field ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text("Your note on this…", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                        field()
                    }
                },
            )
        }
        Box(
            Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Remove note", tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

/** A sent message's notes, as quote cards in its bubble; tap one to jump to the part it quotes. */
@Composable
fun SentNotes(notes: List<ReplyNote>, ink: Color, onJump: (ReplyNote) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        notes.forEachIndexed { i, note ->
            val tone = noteColor(i + 1)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x22000000))
                    .drawBehind { drawRect(tone, Offset.Zero, Size(3.dp.toPx(), size.height)) }
                    .clickable(enabled = note.ref != null) { onJump(note) }
                    .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NoteBadge(i + 1, small = true)
                    Text(
                        note.quote.replace(Regex("\\s+"), " "),
                        style = MaterialTheme.typography.labelMedium,
                        color = ink.copy(alpha = 0.72f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (note.note.isNotBlank()) Text(note.note, style = MaterialTheme.typography.bodyMedium, color = ink)
            }
        }
    }
}

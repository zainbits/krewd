package app.krewd.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.krewd.LocalKrewdColors
import app.krewd.R
import app.krewd.UiState
import app.krewd.data.ChatRef
import app.krewd.data.EngineInfo
import app.krewd.data.LinkItem
import app.krewd.data.Proposal
import app.krewd.data.SessionInfo
import app.krewd.data.UserItem

// Chats that refer to and work for each other: @ in the composer, the strip of linked chats in a chat, the rows
// for side sessions, replies sent back and messages from other chats, and agents' proposals.

/** The word at the cursor when it starts with @: the chat being looked for. */
data class MentionQuery(val start: Int, val end: Int, val typed: String)

fun mentionQuery(text: String, cursor: Int): MentionQuery? {
    if (cursor < 0 || cursor > text.length) return null
    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    var end = cursor
    while (end < text.length && !text[end].isWhitespace()) end++
    val token = text.substring(start, end)
    return if (token.startsWith("@")) MentionQuery(start, end, token.drop(1)) else null
}

/** [value] without the @word at [query]: the picked chat goes into a chip, not the text. */
fun removeMention(value: TextFieldValue, query: MentionQuery): TextFieldValue {
    val before = value.text.substring(0, query.start)
    var tail = value.text.substring(query.end)
    if (before.isEmpty() || before.endsWith(" ") || before.endsWith("\n")) tail = tail.removePrefix(" ")
    return TextFieldValue(before + tail, TextRange(before.length))
}

data class MentionCandidate(val session: SessionInfo, val tag: String?, val archived: Boolean) {
    fun ref(): ChatRef = ChatRef(session.id, session.title)
}

/**
 * The chats @ offers from the chat [currentId] (null: a new task): its own side sessions and sub-agents first, then
 * its parent, then every other chat newest first; archived chats only when the typed text matches one.
 */
fun mentionCandidates(state: UiState, currentId: String?, typed: String, exclude: Set<String>): List<MentionCandidate> {
    val q = typed.trim().lowercase()
    fun match(s: SessionInfo) = q.isEmpty() || s.title.lowercase().contains(q)
    val current = state.session(currentId)
    val open = state.sessions.filter { it.id != currentId && it.id !in exclude && !it.archived }
    val children = if (currentId == null) emptyList() else open.filter { it.parentId == currentId }.sortedByDescending { it.updatedAt }
    val parent = open.firstOrNull { it.id == current?.parentId }
    val rest = open.filter { it !in children && it != parent }.sortedByDescending { it.updatedAt }
    val found = children.filter(::match).map { MentionCandidate(it, it.sideLabel, false) } +
        listOfNotNull(parent?.takeIf(::match)?.let { MentionCandidate(it, "Parent", false) }) +
        rest.filter(::match).map { MentionCandidate(it, it.sideLabel, false) }
    val openIds = open.mapTo(HashSet()) { it.id }
    val archived = if (q.isEmpty()) emptyList() else state.archived.orEmpty()
        .filter { it.id != currentId && it.id !in exclude && it.id !in openIds && match(it) }
        .sortedByDescending { it.updatedAt }
        .map { MentionCandidate(it, it.sideLabel, true) }
    return (found + archived).take(40)
}

/** The chats to pick from while an @word is typed, above the text (the look of the command suggestions). */
@Composable
fun ChatSuggestions(candidates: List<MentionCandidate>, engines: List<EngineInfo>, onPick: (MentionCandidate) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerHighest) {
        if (candidates.isEmpty()) {
            Text(
                "No chat by that name",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
            return@Surface
        }
        LazyColumn(Modifier.heightIn(max = 280.dp).padding(vertical = 4.dp)) {
            candidates.forEachIndexed { i, c ->
                if (c.archived && (i == 0 || !candidates[i - 1].archived)) {
                    item(key = "archived-header") {
                        Text(
                            "Archived",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                }
                item(key = c.session.id) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onPick(c) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CrewFace(lookFor(c.session.engine, engines), faceOf(c.session, null), 24.dp, animate = false)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    c.session.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                c.tag?.let { SideTag(it) }
                            }
                            Text(
                                listOfNotNull(engines.firstOrNull { it.id == c.session.engine }?.name ?: c.session.engine, statusWord(c.session.status), shortAge(c.session.updatedAt).ifBlank { null })
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        StatusDot(c.session.status)
                    }
                }
            }
        }
    }
}

private fun statusWord(status: String): String = when (status) {
    "running" -> "Working"
    "waiting" -> "Needs you"
    "error" -> "Stopped with an error"
    else -> "Idle"
}

/** A small dot for a chat's state: working, needs you, error, or idle. */
@Composable
fun StatusDot(status: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val color = when (status) {
        "running" -> colors.primary
        "waiting" -> LocalKrewdColors.current.needs
        "error" -> colors.error
        else -> colors.outlineVariant
    }
    Box(modifier.size(8.dp).background(color, CircleShape).semantics { contentDescription = statusWord(status) })
}

/** "Side", "Sub-agent" or "Parent": a quiet label next to a chat's title. */
@Composable
fun SideTag(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(6.dp), color = colors.secondaryContainer, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Chats attached to the unsent message, as chips beside its files and tabs; tap one to remove it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DraftChatChips(chats: List<ChatRef>, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    if (chats.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chats.forEach { chat ->
            InputChip(
                selected = false,
                onClick = { onRemove(chat.id) },
                label = { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_chat_24), contentDescription = null, modifier = Modifier.size(16.dp)) },
                trailingIcon = { Icon(painterResource(R.drawable.ic_close_24), contentDescription = "Remove ${chat.title}", modifier = Modifier.size(16.dp)) },
                colors = InputChipDefaults.inputChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
    }
}

/** Chats attached to a sent message, inside its bubble; tap one to open it ("Deleted chat" once it is gone). */
@Composable
fun SentChatChips(chats: List<ChatRef>, exists: (String) -> Boolean, color: Color, onOpen: (String) -> Unit) {
    chats.forEach { chat -> ChatChip(chat, exists(chat.id), color, Color(0x22000000), onOpen) }
}

/** One chat as a small rounded chip: the chat icon, its title and a chevron; the press ripple keeps the shape. */
@Composable
private fun ChatChip(chat: ChatRef, there: Boolean, color: Color, background: Color, onOpen: (String) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .clip(shape)
            .background(background, shape)
            .then(if (there) Modifier.clickable { onOpen(chat.id) } else Modifier)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_chat_24), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(
            if (there) chat.title else "Deleted chat",
            style = MaterialTheme.typography.labelMedium,
            color = if (there) color else color.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp),
        )
        if (there) Icon(painterResource(R.drawable.ic_chevron_right_24), contentDescription = "Open ${chat.title}", tint = color.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
    }
}

/** The chats an agent's reply named (by id), under the reply: the same chips as attached chats, each opens its chat. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NamedChatChips(chats: List<ChatRef>, title: (String) -> String?, exists: (String) -> Boolean, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chats.forEach { chat ->
            ChatChip(chat.copy(title = title(chat.id) ?: chat.title), exists(chat.id), colors.onSurfaceVariant, colors.surfaceContainerHigh, onOpen)
        }
    }
}

/** The titles of the chats this phone knows, for chat ids in agents' replies; null when unknown. */
val LocalChatTitles = androidx.compose.runtime.staticCompositionLocalOf<(String) -> String?> { { null } }

private const val UUID_RE = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
private val CHAT_MENTION = Regex(
    "\\[([^\\]\n]*)\\]\\(krewd://chat/($UUID_RE)\\)" +       // [label](krewd://chat/<id>)
        "|`(?:krewd://chat/)?($UUID_RE)`" +                        // `<id>` or `krewd://chat/<id>`
        "|krewd://chat/($UUID_RE)(?![0-9a-fA-F-])" +              // krewd://chat/<id>
        "|(?<![\\w/=?&#.%-])($UUID_RE)(?![\\w-])", // bare (not part of a web address or a longer word)
)

/**
 * [text] with every known chat id (bare, in backticks or as a krewd://chat/ link) turned into a Markdown link to
 * `krewd://chat/<id>` whose text is the chat's title, so a reply never shows a raw id. Code blocks stay as written;
 * an unknown id (another kind of id, a deleted chat) is left alone.
 */
fun linkChats(text: String, title: (String) -> String?): String {
    if (!text.contains('-')) return text
    val out = StringBuilder()
    var inFence = false
    text.split('\n').forEachIndexed { i, line ->
        if (i > 0) out.append('\n')
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = !inFence
            out.append(line)
            return@forEachIndexed
        }
        if (inFence) {
            out.append(line)
            return@forEachIndexed
        }
        out.append(
            CHAT_MENTION.replace(line) { m ->
                val id = (m.groups[2] ?: m.groups[3] ?: m.groups[4] ?: m.groups[5])!!.value.lowercase()
                val name = title(id) ?: return@replace m.value
                val label = m.groups[1]?.value?.takeIf { it.isNotBlank() && !Regex(UUID_RE).containsMatchIn(it) }
                "[" + escapeLinkText(label ?: name) + "](krewd://chat/" + id + ")"
            },
        )
    }
    return out.toString()
}

private fun escapeLinkText(text: String): String = text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("`", "'")

/** The chat id of a krewd://chat/<id> link, or null. */
fun chatLinkId(uri: String): String? =
    Regex("^krewd://chat/($UUID_RE)$").find(uri.trim())?.groupValues?.get(1)?.lowercase()

/**
 * Under a chat's title: its parent, its side sessions and sub-agents, and the chats it waits on for a reply, each a
 * tap away, so nothing running is forgotten. Nothing shows for an ordinary chat.
 */
@Composable
fun LinkedChatsStrip(state: UiState, session: SessionInfo, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val children = state.sessions.filter { it.parentId == session.id && !it.archived }.sortedByDescending { it.updatedAt }
    val childIds = children.mapTo(HashSet()) { it.id }
    val prompted = session.waitingOn.filter { it !in childIds }.mapNotNull { state.session(it) }
    val parentId = session.parentId
    if (parentId == null && children.isEmpty() && prompted.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (session.waitingOn.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(16.dp), color = LocalKrewdColors.current.needsContainer) {
                Text(
                    "Waiting on ${session.waitingOn.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
        if (parentId != null) {
            val parent = state.session(parentId)
            LinkChip(
                title = "↑ " + (parent?.title ?: session.parentTitle ?: "Parent chat"),
                status = parent?.status,
                tag = null,
                description = "Open the parent chat",
                enabled = parent != null,
                onClick = { onOpen(parentId) },
            )
        }
        children.forEach { child ->
            LinkChip(
                title = child.title,
                status = child.status,
                tag = if (child.id in session.waitingOn) "${child.sideLabel} · waiting" else child.sideLabel,
                description = "Open ${child.sideLabel?.lowercase() ?: "chat"} ${child.title}",
                onClick = { onOpen(child.id) },
            )
        }
        prompted.forEach { chat ->
            LinkChip(
                title = chat.title,
                status = chat.status,
                tag = "waiting",
                description = "Open ${chat.title}, which owes this chat a reply",
                onClick = { onOpen(chat.id) },
            )
        }
    }
}

@Composable
private fun LinkChip(title: String, status: String?, tag: String?, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (status != null) StatusDot(status)
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
            tag?.let { SideTag(it) }
        }
    }
}

/** This chat started a side session or sub-agent, or sent a message to another chat: a quiet line to open it. */
@Composable
fun LinkRow(item: LinkItem, exists: Boolean, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = when (item.kind) {
        "subagent" -> "Started sub-agent"
        "prompt" -> if (item.returns) "Asked" else "Sent a message to"
        else -> "Started side session"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(if (exists) Modifier.clickable(onClick = onOpen) else Modifier)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(painterResource(R.drawable.ic_chat_24), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(
                "$label: ${if (exists) item.title else item.title + " (deleted)"}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 280.dp),
            )
            if (exists) Icon(painterResource(R.drawable.ic_chevron_right_24), contentDescription = "Open ${item.title}", tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * A reply another chat sent back ([UserItem.purpose] "result"), or a message from another chat ("prompt"): a card,
 * not the owner's bubble. Its header opens the chat it came from; a long reply folds.
 */
@Composable
fun FromChatRow(item: UserItem, exists: Boolean, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val result = item.purpose == "result"
    val from = item.fromTitle ?: "another chat"
    val header = when {
        !result -> "From $from"
        !item.ok -> "Note from $from"
        item.fromKind == "sub-agent" -> "Reply from sub-agent $from"
        else -> "Reply from $from"
    }
    // The server's text starts with a line saying where it came from; the header says that already.
    val body = if (item.ok || !result) item.text.substringAfter("\n\n", item.text) else item.text
    val long = body.length > 700
    var open by remember(item.key) { mutableStateOf(!long) }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (result && !item.ok) colors.surfaceContainer else colors.tertiaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (exists) Modifier.clickable(onClick = onOpen) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_chat_24), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(header, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (exists) {
                    Text("Open", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    Icon(painterResource(R.drawable.ic_chevron_right_24), contentDescription = "Open $from", tint = colors.primary, modifier = Modifier.size(16.dp))
                }
            }
            if (result && !item.ok) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            } else if (open) {
                SelectionContainer { Markdown(body) }
            } else {
                Text(plainPreview(body), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (long) {
                TextButton(onClick = { open = !open }, modifier = Modifier.align(Alignment.End)) { Text(if (open) "Fold" else "Show all") }
            }
            if (item.state == "queued" || item.state == "steering") {
                Text("Waiting for the current turn", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/**
 * An agent's proposal: who proposes what, its title and opening lines, and Review (pending) or what became of it.
 * [chatTitle] names the proposing chat (shown under Needs you, not in the chat itself).
 */
@Composable
fun ProposalCard(
    proposal: Proposal,
    chatTitle: String?,
    look: KrewdLook?,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
    onOpenResult: ((String) -> Unit)?,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val krewd = LocalKrewdColors.current
    Surface(
        onClick = { if (proposal.pending) onReview() else proposal.resultId?.let { id -> onOpenResult?.invoke(id) } },
        enabled = proposal.pending || (proposal.resultId != null && onOpenResult != null),
        shape = shape,
        color = colors.surfaceContainer,
        border = if (proposal.pending) BorderStroke(1.dp, krewd.needs.copy(alpha = 0.6f)) else null,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 14.dp, top = 13.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (look != null) CrewFace(look, if (proposal.pending) FaceState.NeedsYou else FaceState.Done, 22.dp, animate = proposal.pending)
                else Icon(painterResource(R.drawable.ic_chat_24), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    listOfNotNull(chatTitle, "proposes ${proposal.what}").joinToString(" "),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(shortAge(proposal.createdAt), style = MaterialTheme.typography.labelMedium, color = colors.outline)
            }
            if (proposal.title.isNotBlank() && !proposal.isPrompt) {
                Text(proposal.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(plainPreview(proposal.text), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (proposal.status) {
                        "pending" -> if (proposal.isPrompt) (if (proposal.returns) "Its reply would come back here" else "No reply would come back")
                        else if (proposal.returns) "Its replies would come back here" else "Nothing would come back"
                        "sent" -> if (proposal.resultId != null && onOpenResult != null) "Sent · tap to open" else "Sent"
                        "dismissed" -> "Dismissed"
                        else -> "Not sent: the chat it was for is gone"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.outline,
                    modifier = Modifier.weight(1f),
                )
                if (proposal.pending) {
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = onReview, colors = ButtonDefaults.buttonColors(containerColor = krewd.needs, contentColor = krewd.onNeeds)) {
                        Text("Review", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

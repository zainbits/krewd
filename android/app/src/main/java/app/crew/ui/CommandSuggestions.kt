package app.crew.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.crew.data.AgentCommand

/** The word being typed at the cursor when it can name a command: `/x` at the start of the message, `$x` anywhere. */
data class CommandQuery(val start: Int, val end: Int, val token: String)

fun commandQuery(text: String, cursor: Int): CommandQuery? {
    if (cursor < 0 || cursor > text.length) return null
    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    var end = cursor
    while (end < text.length && !text[end].isWhitespace()) end++
    val token = text.substring(start, end)
    return when {
        token.startsWith("/") && text.substring(0, start).isBlank() -> CommandQuery(start, end, token)
        token.startsWith("$") -> CommandQuery(start, end, token)
        else -> null
    }
}

/** The agent's commands that fit [query]: names starting with it first, then names containing it. */
fun matchCommands(commands: List<AgentCommand>, query: CommandQuery?): List<AgentCommand> {
    if (query == null) return emptyList()
    val sigil = query.token.first()
    val typed = query.token.drop(1).lowercase()
    val own = commands.filter { it.label.first() == sigil }
    val name = { c: AgentCommand -> c.label.drop(1).lowercase() }
    val starts = own.filter { name(it).startsWith(typed) }
    val contains = if (typed.isEmpty()) emptyList() else own.filter {
        it !in starts && name(it).contains(typed)
    }
    val found = starts + contains
    // Typed out in full: nothing left to suggest.
    return if (found.size == 1 && found[0].label.equals(query.token, ignoreCase = true)) emptyList() else found
}

/** [value] with the word at [query] replaced by the command, followed by a space. */
fun applyCommand(value: TextFieldValue, query: CommandQuery, command: AgentCommand): TextFieldValue {
    val tail = value.text.substring(query.end)
    val insert = command.label + if (tail.startsWith(" ")) "" else " "
    val text = value.text.substring(0, query.start) + insert + tail
    val cursor = query.start + insert.length + if (tail.startsWith(" ")) 1 else 0
    return TextFieldValue(text, TextRange(cursor.coerceAtMost(text.length)))
}

/** A list of the agent's commands and skills to pick from, shown above the text while one is being typed. */
@Composable
fun CommandSuggestions(matches: List<AgentCommand>, onPick: (AgentCommand) -> Unit, modifier: Modifier = Modifier) {
    if (matches.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceContainerHighest,
    ) {
        LazyColumn(Modifier.heightIn(max = 260.dp).padding(vertical = 4.dp)) {
            items(matches, key = { it.name }) { command ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(command) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            command.label,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                        )
                        command.hint?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (command.description.isNotBlank()) {
                        Text(
                            command.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private val WORD = Regex("\\S+")

/**
 * Where the agent will take the text as one of its commands or skills, the same rule the agents use: one of its own
 * `/name` opening the message, or one of its `$name` (Codex skills) anywhere. Everything else is plain text.
 */
fun commandRanges(text: String, commands: List<AgentCommand>): List<IntRange> {
    if (commands.isEmpty() || ('/' !in text && '$' !in text)) return emptyList()
    val labels = commands.mapTo(HashSet()) { it.label }
    return WORD.findAll(text).mapNotNull { match ->
        val word = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', '"', '\'')
        val start = match.range.first
        val known = word in labels && when (word.first()) {
            '/' -> text.substring(0, start).isBlank()
            '$' -> true
            else -> false
        }
        if (known) start until start + word.length else null
    }.toList()
}

/** [text] with [ranges] in the command ink. */
fun highlightCommands(text: String, ranges: List<IntRange>, ink: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { addStyle(SpanStyle(color = ink, fontWeight = FontWeight.SemiBold), it.first, it.last + 1) }
}

/** Colours the agent's commands in a text field as they are typed; the pill behind them is [commandPills]. */
data class CommandHighlight(val commands: List<AgentCommand>, val ink: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlightCommands(text.text, commandRanges(text.text, commands), ink), OffsetMapping.Identity)
}

/** A soft rounded pill behind each of [ranges] of the text laid out in [layout] (one per line it spans). */
fun Modifier.commandPills(layout: TextLayoutResult?, ranges: List<IntRange>, fill: Color): Modifier =
    if (ranges.isEmpty()) this else drawBehind {
        val laid = layout ?: return@drawBehind
        val length = laid.layoutInput.text.length
        val padX = 4.dp.toPx()
        val padY = 1.dp.toPx()
        for (range in ranges) {
            if (range.last >= length) continue
            val firstLine = laid.getLineForOffset(range.first)
            val lastLine = laid.getLineForOffset(range.last)
            for (line in firstLine..lastLine) {
                val from = if (line == firstLine) range.first else laid.getLineStart(line)
                val to = if (line == lastLine) range.last else laid.getLineEnd(line, visibleEnd = true) - 1
                if (to < from) continue
                val left = laid.getBoundingBox(from).left
                val right = laid.getBoundingBox(to).right
                val top = laid.getLineTop(line) + padY
                val height = laid.getLineBottom(line) - laid.getLineTop(line) - 2 * padY
                drawRoundRect(
                    fill,
                    topLeft = Offset(left - padX, top),
                    size = Size(right - left + 2 * padX, height),
                    cornerRadius = CornerRadius(height / 2, height / 2),
                )
            }
        }
    }

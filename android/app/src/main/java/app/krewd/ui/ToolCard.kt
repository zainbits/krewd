package app.krewd.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.krewd.data.ToolItem
import org.json.JSONArray
import org.json.JSONObject

/** Colours of the terminal panel. Fixed on purpose: a console looks like a console in light and dark themes. */
private object Term {
    val Background = Color(0xFF0F1114)
    val Border = Color(0xFF23272E)
    val Prompt = Color(0xFF7EE787)
    val Command = Color(0xFFE6EDF3)
    val Output = Color(0xFFA9B4C0)
    val Muted = Color(0xFF6E7681)
    val Error = Color(0xFFFF7B72)
}

/** What a tool call shows: a shell command with its console output, or a few labelled fields. */
internal class ToolView(
    val label: String,
    val glyph: String,
    val command: String?,
    val description: String?,
    val fields: List<Pair<String, String>>,
    val output: String?,
)

private val fence = Regex("^```[\\w+-]*\\n(.*?)\\n?```\\s*$", RegexOption.DOT_MATCHES_ALL)

/** Output the agent wrapped in a ```console fence is shown as plain console text. */
internal fun unfence(text: String): String {
    val trimmed = text.trim('\n', '\r')
    return fence.matchEntire(trimmed)?.groupValues?.get(1)?.trimEnd() ?: trimmed.trimEnd()
}

private fun JSONObject.display(key: String): String = when (val v = opt(key)) {
    null, JSONObject.NULL -> ""
    is JSONArray -> (0 until v.length()).joinToString(" ") { v.opt(it).toString() }
    is String -> v
    else -> v.toString()
}

internal fun toolView(item: ToolItem): ToolView {
    val json = item.input?.let { runCatching { JSONObject(it) }.getOrNull() }
    val kind = item.kind
    val command = json?.display("command")?.takeIf { it.isNotBlank() && (kind == "execute" || kind == null || kind == "other") }
        ?: item.title.takeIf { kind == "execute" && json == null }
    val shell = command != null || kind == "execute"
    val (label, glyph) = when {
        shell -> "Shell" to ">_"
        kind == "read" -> "Read" to "≡"
        kind == "edit" || kind == "delete" || kind == "move" -> "Edit" to "✎"
        kind == "search" -> "Search" to "⌕"
        kind == "fetch" -> "Web" to "↗"
        kind == "think" -> "Think" to "…"
        else -> "Tool" to "·"
    }
    val hidden = setOf("command", "description", "timeout")
    val fields = json?.keys()?.asSequence()?.filter { it !in hidden || !shell }?.map { it to json.display(it) }
        ?.filter { it.second.isNotBlank() }?.toList()
        ?: if (shell || item.input == null) emptyList() else listOf("input" to item.input)
    return ToolView(
        label = label,
        glyph = glyph,
        command = if (shell) command ?: item.title else null,
        description = json?.display("description")?.takeIf { it.isNotBlank() },
        fields = fields,
        output = item.output?.let(::unfence)?.takeIf { it.isNotBlank() },
    )
}

@Composable
internal fun ToolCard(item: ToolItem, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val view = remember(item.input, item.output, item.kind, item.title) { toolView(item) }
    val failed = item.status == "failed"
    val scheme = MaterialTheme.colorScheme
    val hold = LocalHoldRow.current
    val holdThis = { hold(item.key) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        border = if (failed) BorderStroke(1.dp, scheme.error.copy(alpha = 0.5f)) else null,
        // onClick on the Surface: the press ripple follows the card's rounded shape.
        onClick = { holdThis(); open = !open },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(scheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(view.glyph, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                }
                if (open) {
                    Column(Modifier.weight(1f)) {
                        Text(view.label, style = MaterialTheme.typography.labelLarge)
                        view.description?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Text(
                        view.command ?: item.title,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                ToolStatusMark(item.status)
            }
            if (open) {
                if (view.command != null) {
                    Terminal(view.command, view.output, failed, holdThis)
                } else {
                    if (view.fields.isNotEmpty()) FieldList(view.fields)
                    view.output?.let { Terminal(null, it, failed, holdThis) }
                }
                content()
            }
        }
    }
}

/** A console: green prompt, the command, a hairline, then what it printed. */
@Composable
private fun Terminal(command: String?, output: String?, failed: Boolean, hold: () -> Unit) {
    var all by remember { mutableStateOf(false) }
    val lines = output?.lines().orEmpty()
    val limit = 14
    val clipped = !all && lines.size > limit
    val shown = if (clipped) lines.take(limit).joinToString("\n") else output
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Term.Background,
        border = BorderStroke(1.dp, if (failed) Term.Error.copy(alpha = 0.6f) else Term.Border),
    ) {
        SelectionContainer {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (command != null) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Term.Prompt, fontWeight = FontWeight.Bold)) { append("$ ") }
                            withStyle(SpanStyle(color = Term.Command)) { append(command.trim()) }
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                    )
                }
                if (shown != null) {
                    if (command != null) Box(Modifier.fillMaxWidth().size(1.dp).background(Term.Border))
                    Text(
                        shown,
                        color = if (failed) Term.Error else Term.Output,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                } else if (command != null) {
                    Text("no output", color = Term.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                if (lines.size > limit) {
                    Text(
                        if (all) "Show less" else "Show all ${lines.size} lines",
                        color = Term.Muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { hold(); all = !all },
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldList(fields: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        fields.forEach { (key, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 8,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ToolStatusMark(status: String?) {
    when (status) {
        "completed" -> Text("✓", color = Term.Prompt.copy(alpha = 0.9f), style = MaterialTheme.typography.labelLarge)
        "failed" -> Text("✕", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
        else -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    }
}

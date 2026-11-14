/**
 * Markdown rendering for agent replies, built on commonmark-java (with GFM tables, strikethrough
 * and autolinks) and drawn with Compose Material 3.
 *
 * Renders headings, paragraphs, bullet and ordered lists (nested, tight or loose), block quotes,
 * thematic breaks, fenced and indented code blocks (language label, Wrap toggle, Copy button),
 * GFM tables (aligned, horizontally scrollable columns), HTML as plain text, and inline emphasis,
 * strong, strikethrough, code spans, links, image alt text and line breaks. Partial (streaming)
 * Markdown is fine: commonmark always yields a valid tree for any prefix of a document.
 */
package app.krewd.ui

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.node.Image as MdImage
import org.commonmark.node.Text as MdText

private val parser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create()))
    .build()

private val TABLE_MAX_COLUMN = 280.dp
private const val COPIED_MS = 1_500L

/** Theme-derived values the (non-composable) inline builder needs. */
private data class MdStyle(val codeBackground: Color, val links: TextLinkStyles, val uriHandler: UriHandler)

/** Renders [text] as Markdown. Wrap in a SelectionContainer to make it selectable. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val document = remember(text) { parser.parse(text) }
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val style = remember(colors, uriHandler) {
        MdStyle(
            codeBackground = colors.surfaceContainerHighest,
            links = TextLinkStyles(style = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)),
            uriHandler = uriHandler,
        )
    }
    Box(modifier) { Blocks(document, style, depth = 0) }
}

/** Markdown reduced to one plain line (marks stripped, whitespace collapsed), for notifications. */
fun markdownPreview(text: String): String {
    val out = StringBuilder()
    fun walk(node: Node) {
        when (node) {
            is MdText -> out.append(node.literal)
            is Code -> out.append(node.literal)
            is FencedCodeBlock -> out.append(' ').append(node.literal.orEmpty())
            is IndentedCodeBlock -> out.append(' ').append(node.literal.orEmpty())
            is SoftLineBreak, is HardLineBreak -> out.append(' ')
            else -> {
                node.children().forEach { walk(it) }
                // Separate blocks (paragraphs, list items, cells) so their words do not run together.
                if (node is org.commonmark.node.Block) out.append(' ')
            }
        }
    }
    walk(parser.parse(text))
    return out.toString().replace(Regex("\\s+"), " ").trim()
}

private fun Node.children(): List<Node> = buildList {
    var child = firstChild
    while (child != null) { add(child); child = child.next }
}

// ---- Blocks ----

@Composable
private fun Blocks(parent: Node, style: MdStyle, depth: Int, gap: Dp = 8.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        for (node in parent.children()) Block(node, style, depth)
    }
}

@Composable
private fun Block(node: Node, style: MdStyle, depth: Int) {
    val body = MaterialTheme.typography.bodyLarge
    val onSurface = MaterialTheme.colorScheme.onSurface
    when (node) {
        is Heading -> Text(remember(node, style) { inlineText(node, style) }, style = headingStyle(node.level), color = onSurface)
        is Paragraph -> Text(remember(node, style) { inlineText(node, style) }, style = body, color = onSurface)
        is ListBlock -> ListView(node, style, depth)
        is BlockQuote -> Quote(node, style, depth)
        is ThematicBreak -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        is FencedCodeBlock -> CodeBlock(
            code = node.literal.orEmpty().removeSuffix("\n"),
            language = node.info?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() },
        )
        is IndentedCodeBlock -> CodeBlock(code = node.literal.orEmpty().removeSuffix("\n"), language = null)
        is TableBlock -> Table(node, style)
        is HtmlBlock -> Text(node.literal.orEmpty().trimEnd(), style = body, color = onSurface)
        is LinkReferenceDefinition -> Unit
        else -> if (node.firstChild != null) Blocks(node, style, depth)
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle {
    val t = MaterialTheme.typography
    val base = when (level) {
        1 -> t.titleLarge
        2 -> t.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp)
        3 -> t.titleMedium
        else -> t.titleSmall
    }
    return base.copy(fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ListView(list: ListBlock, style: MdStyle, depth: Int) {
    val start = (list as? OrderedList)?.markerStartNumber ?: 1
    val bullet = when (depth % 3) { 0 -> "•"; 1 -> "◦"; else -> "▪" }
    val itemGap = if (list.isTight) 2.dp else 8.dp
    Column(verticalArrangement = Arrangement.spacedBy(itemGap)) {
        list.children().forEachIndexed { index, item ->
            Row {
                Text(
                    text = if (list is OrderedList) "${start + index}." else bullet,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 18.dp).padding(end = 8.dp),
                )
                Box(Modifier.weight(1f)) {
                    Blocks(item, style, depth + 1, gap = if (list.isTight) 4.dp else 8.dp)
                }
            }
        }
    }
}

@Composable
private fun Quote(quote: BlockQuote, style: MdStyle, depth: Int) {
    val bar = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .drawBehind { drawRect(bar, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 15.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) { Blocks(quote, style, depth) }
}

@Composable
private fun CodeBlock(code: String, language: String?) {
    var wrap by remember { mutableStateOf(false) }
    var copies by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    LaunchedEffect(copies) {
        if (copies > 0) { copied = true; delay(COPIED_MS); copied = false }
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            DisableSelection {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = language.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    BarButton("Wrap", active = wrap) { wrap = !wrap }
                    BarButton(if (copied) "Copied" else "Copy", active = copied) {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                            copies++
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Box(Modifier.fillMaxWidth().then(if (wrap) Modifier else Modifier.horizontalScroll(scroll))) {
                SelectionContainer {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurface,
                        softWrap = wrap,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BarButton(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun Table(table: TableBlock, style: MdStyle) {
    // Rows from the head and body sections; each cell paired with its rendered text.
    val rows = remember(table, style) {
        table.children().flatMap { section ->
            section.children().filterIsInstance<TableRow>().map { row ->
                (section is TableHead) to row.children().filterIsInstance<TableCell>().map { it to inlineText(it, style) }
            }
        }
    }
    if (rows.isEmpty()) return
    val columns = rows.maxOf { it.second.size }
    if (columns == 0) return
    val colors = MaterialTheme.colorScheme
    val text = MaterialTheme.typography.bodyMedium
    val headerText = text.copy(fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widths = remember(rows, text, density) {
        val capPx = with(density) { TABLE_MAX_COLUMN.roundToPx() }
        val px = IntArray(columns)
        for ((header, cells) in rows) cells.forEachIndexed { i, (cell, content) ->
            val measured = measurer.measure(content, style = if (header || cell.isHeader) headerText else text, softWrap = false, maxLines = 1)
            px[i] = maxOf(px[i], minOf(measured.size.width + 1, capPx))
        }
        px.map { with(density) { it.toDp() } + 16.dp }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.clip(shape).border(1.dp, colors.outlineVariant, shape)) {
            for ((header, cells) in rows) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    for (i in 0 until columns) {
                        val pair = cells.getOrNull(i)
                        val isHeader = header || pair?.first?.isHeader == true
                        Box(
                            Modifier
                                .width(widths[i])
                                .fillMaxHeight()
                                .background(if (isHeader) colors.surfaceContainerHigh else Color.Transparent)
                                .border(0.5.dp, colors.outlineVariant)
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            Text(
                                text = pair?.second ?: AnnotatedString(""),
                                style = if (isHeader) headerText else text,
                                color = colors.onSurface,
                                textAlign = when (pair?.first?.alignment) {
                                    TableCell.Alignment.CENTER -> TextAlign.Center
                                    TableCell.Alignment.RIGHT -> TextAlign.End
                                    else -> TextAlign.Start
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- Inlines ----

private fun inlineText(parent: Node, style: MdStyle): AnnotatedString = buildAnnotatedString { appendInlines(parent, style) }

private fun AnnotatedString.Builder.appendInlines(parent: Node, style: MdStyle) {
    var node = parent.firstChild
    while (node != null) { appendInline(node, style); node = node.next }
}

private fun AnnotatedString.Builder.appendInline(node: Node, style: MdStyle) {
    when (node) {
        is MdText -> append(node.literal.orEmpty())
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(node, style) }
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(node, style) }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(node, style) }
        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = style.codeBackground, fontSize = 0.9.em)) {
            append(node.literal.orEmpty())
        }
        is Link -> appendLink(node.destination, style) { appendInlines(node, style) }
        is MdImage -> appendLink(node.destination, style) {
            val alt = markdownPlain(node)
            append(alt.ifBlank { node.destination.orEmpty() })
        }
        is SoftLineBreak -> append(' ')
        is HardLineBreak -> append('\n')
        is HtmlInline -> append(node.literal.orEmpty())
        else -> appendInlines(node, style)
    }
}

private fun AnnotatedString.Builder.appendLink(url: String?, style: MdStyle, content: AnnotatedString.Builder.() -> Unit) {
    val destination = url.orEmpty()
    if (destination.isBlank()) { content(); return }
    // Explicit listener so a link no app can open (relative path, odd scheme) never crashes.
    val listener = LinkInteractionListener { runCatching { style.uriHandler.openUri(destination) } }
    withLink(LinkAnnotation.Url(destination, style.links, listener)) { content() }
}

/** Plain text of an inline subtree (used for image alt text). */
private fun markdownPlain(node: Node): String = buildString {
    fun walk(n: Node) {
        when (n) {
            is MdText -> append(n.literal)
            is Code -> append(n.literal)
            is SoftLineBreak, is HardLineBreak -> append(' ')
            else -> n.children().forEach { walk(it) }
        }
    }
    walk(node)
}

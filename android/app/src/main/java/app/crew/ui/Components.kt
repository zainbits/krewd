package app.crew.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.crew.Connection
import app.crew.constants.ShellDimensions

/** Floating pill used for titles and toolbars. */
@Composable
fun FloatingPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(ShellDimensions.PILL_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Box(Modifier.height(ShellDimensions.TITLE_PILL_HEIGHT), contentAlignment = Alignment.CenterStart) {
            content()
        }
    }
}

@Composable
fun TitlePill(title: String, connection: Connection, modifier: Modifier = Modifier) {
    FloatingPill(modifier) {
        Row(
            modifier = Modifier.padding(horizontal = ShellDimensions.TITLE_PILL_HORIZONTAL_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.semantics { heading() },
            )
            ConnectionDot(connection)
        }
    }
}

@Composable
fun ConnectionDot(connection: Connection) {
    val (color, label) = when (connection) {
        is Connection.Online -> MaterialTheme.colorScheme.primary to connection.route
        Connection.Connecting -> MaterialTheme.colorScheme.outline to "Connecting"
        Connection.NotSetUp -> MaterialTheme.colorScheme.outline to "Set up"
        is Connection.Offline -> MaterialTheme.colorScheme.error to "Offline"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatusChip(status: String, pending: Int = 0) {
    val (label, color) = when {
        pending > 0 || status == "waiting" -> "Needs you" to MaterialTheme.colorScheme.tertiary
        status == "running" -> "Working" to MaterialTheme.colorScheme.primary
        status == "error" -> "Error" to MaterialTheme.colorScheme.error
        else -> "Done" to MaterialTheme.colorScheme.outline
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainer, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ShellDimensions.CARD_CORNER),
        color = color,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun MonoBlock(text: String, maxLines: Int = Int.MAX_VALUE) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(10.dp),
        )
    }
}

/** Short, human time for list rows: "now", "5m", "3h", or the date. */
fun shortAge(iso: String): String {
    val then = runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull() ?: return ""
    val minutes = java.time.Duration.between(then, java.time.Instant.now()).toMinutes()
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h"
        else -> java.time.format.DateTimeFormatter.ofPattern("d MMM").format(then.atZone(java.time.ZoneId.systemDefault()))
    }
}

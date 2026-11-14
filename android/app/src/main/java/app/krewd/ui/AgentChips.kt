package app.krewd.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.krewd.data.ConfigChoice
import app.krewd.data.ConfigOption
import app.krewd.data.SessionInfo

private val MENU_WIDTH = 300.dp

/** A chip showing one agent setting; tapping it lists the agent's own choices. */
@Composable
fun ConfigChip(option: ConfigOption, selected: String?, enabled: Boolean, onPick: (String) -> Unit, note: String? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            enabled = enabled,
            label = { Text("${option.name}: ${option.nameOf(selected) ?: "?"} ▾", maxLines = 1) },
            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(max = MENU_WIDTH)) {
            MenuHeader(option.name, option.description)
            option.choices.forEach { choice ->
                ChoiceItem(choice, choice.value == selected) {
                    open = false
                    if (choice.value != selected) onPick(choice.value)
                }
            }
            if (note != null) {
                HorizontalDivider()
                MenuHeader(null, note)
            }
        }
    }
}

/** A chip naming the agent (harness) and its version; tapping it shows details. */
@Composable
fun InfoChip(label: String, details: List<String>, choices: List<Pair<String, String>> = emptyList(), selected: String? = null, onPick: ((String) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(label + if (onPick != null && choices.size > 1) " ▾" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(max = MENU_WIDTH)) {
            if (onPick != null && choices.size > 1) {
                MenuHeader("Agent", null)
                choices.forEach { (id, name) ->
                    ChoiceItem(ConfigChoice(id, name, null), id == selected) {
                        open = false
                        if (id != selected) onPick(id)
                    }
                }
                if (details.isNotEmpty()) HorizontalDivider()
            }
            details.forEach { MenuHeader(null, it) }
        }
    }
}

@Composable
private fun MenuHeader(title: String?, text: String?) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.labelLarge)
        if (!text.isNullOrBlank()) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChoiceItem(choice: ConfigChoice, current: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(choice.name, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                if (!choice.description.isNullOrBlank() && choice.description != choice.name) {
                    Text(choice.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        leadingIcon = { Text(if (current) "✓" else " ", color = MaterialTheme.colorScheme.primary) },
        onClick = onClick,
    )
}

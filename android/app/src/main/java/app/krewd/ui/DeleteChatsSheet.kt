package app.krewd.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.krewd.KrewdViewModel
import app.krewd.data.OldChats
import app.krewd.data.deleteOldChats
import app.krewd.data.oldChats
import kotlinx.coroutines.delay

private val PRESET_DAYS = listOf(1, 7, 30, 90)
private const val MAX_DAYS = 3650
private const val LISTED = 5

private fun days(n: Int) = if (n == 1) "1 day" else "$n days"
private fun chats(n: Int) = if (n == 1) "1 chat" else "$n chats"

/**
 * Settings > Delete chats: deletes every chat last active in the past N days, or every chat older than that.
 * Pinned chats and chats that are working always stay. The server previews what would go (the counts and the
 * first few titles update as the range changes), and the delete takes exactly the previewed chats, after a
 * confirmation, since it cannot be undone.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeleteChatsSheet(viewModel: KrewdViewModel, onDismiss: () -> Unit) {
    var scope by rememberSaveable { mutableStateOf("recent") }
    var days by rememberSaveable { mutableIntStateOf(7) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var customText by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var preview by remember { mutableStateOf<OldChats?>(null) }
    var loading by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val dayField = remember { FocusRequester() }
    var focusField by remember { mutableStateOf(false) }

    val valid = days in 1..MAX_DAYS
    fun load() {
        if (!valid) { preview = null; return }
        val asked = ++generation
        loading = true
        viewModel.request({ it.oldChats(days, scope) }) { result, problem ->
            if (asked != generation) return@request // a newer range was picked meanwhile
            loading = false
            if (result != null) { preview = result; error = null } else error = problem
        }
    }
    // Typing a number waits a moment so each keystroke does not ask the server.
    LaunchedEffect(days, scope) {
        if (custom) delay(300)
        load()
    }

    val shown = preview?.takeIf { it.days == days && it.scope == scope }
    val range = if (scope == "recent") "last active in the past ${days(days)}" else "last active more than ${days(days)} ago"

    if (confirm && shown != null) {
        val n = shown.chats.size
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${chats(n)}?") },
            text = {
                Text(
                    "Every chat $range goes for good: its history, attachments, images and the agent's record of it. " +
                        "Pinned and working chats stay. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    deleting = true
                    note = null
                    val ids = shown.chats.map { it.id }
                    viewModel.request({ it.deleteOldChats(shown.days, shown.scope, ids) }) { deleted, problem ->
                        deleting = false
                        if (deleted != null) {
                            viewModel.chatsDeleted(deleted)
                            val left = ids.size - deleted.size
                            note = "Deleted ${chats(deleted.size)}" +
                                if (left > 0) ". ${chats(left)} changed meanwhile and stayed." else "."
                            error = null
                        } else error = problem
                        load()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Delete chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (loading || deleting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(
                "Pick a range by when each chat was last active. Pinned chats and chats that are working always stay.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("recent" to "In the last", "older" to "Older than").forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = scope == value,
                        onClick = { scope = value; note = null },
                        enabled = !deleting,
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESET_DAYS.forEach { n ->
                    FilterChip(
                        selected = !custom && days == n,
                        onClick = { custom = false; days = n; note = null; focus.clearFocus() },
                        enabled = !deleting,
                        label = { Text(days(n)) },
                    )
                }
                FilterChip(
                    selected = custom,
                    onClick = {
                        custom = true
                        note = null
                        // The number comes selected, so typing replaces it.
                        val text = customText.text.ifEmpty { days.toString() }
                        customText = TextFieldValue(text, TextRange(0, text.length))
                        focusField = true
                    },
                    enabled = !deleting,
                    label = { Text("Custom") },
                )
            }
            if (custom) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { value ->
                        val digits = value.text.filter(Char::isDigit).take(4)
                        customText = if (digits == value.text) value else TextFieldValue(digits, TextRange(digits.length))
                        days = digits.toIntOrNull() ?: 0
                        note = null
                    },
                    label = { Text("Days") },
                    suffix = { Text(if (days == 1) "day" else "days") },
                    isError = !valid,
                    supportingText = if (!valid) ({ Text("1 to $MAX_DAYS days") }) else null,
                    singleLine = true,
                    enabled = !deleting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.width(180.dp).focusRequester(dayField),
                )
                // Picking Custom opens the keyboard on the number.
                LaunchedEffect(focusField) {
                    if (focusField) { dayField.requestFocus(); focusField = false }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            note?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }

            SectionCard(Modifier.animateContentSize()) {
                when {
                    !valid -> Text("Enter a number of days", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    shown == null -> Text(if (error == null) "Looking…" else "Could not check", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        val n = shown.chats.size
                        Text(if (n == 0) "No chats to delete" else chats(n), style = MaterialTheme.typography.titleMedium)
                        Text(range.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        shown.chats.take(LISTED).forEach { chat ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(chat.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                val tags = listOfNotNull("routine".takeIf { chat.isRoutine }, "archived".takeIf { chat.archived },
                                    shortAge(chat.updatedAt))
                                Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        if (n > LISTED) {
                            Text("and ${n - LISTED} more", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val kept = listOfNotNull(
                            "${shown.keptPinned} pinned".takeIf { shown.keptPinned > 0 },
                            "${shown.keptWorking} working".takeIf { shown.keptWorking > 0 },
                        )
                        if (kept.isNotEmpty()) {
                            Text("Staying: ${kept.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            val count = shown?.chats?.size ?: 0
            Button(
                onClick = { focus.clearFocus(); confirm = true },
                enabled = !deleting && !loading && valid && count > 0,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(
                    when {
                        deleting -> "Deleting…"
                        count > 0 -> "Delete ${chats(count)}"
                        else -> "Nothing to delete"
                    },
                )
            }
        }
    }
}

package app.krewd.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.krewd.Connection
import app.krewd.KrewdViewModel
import app.krewd.NEW_DRAFT
import app.krewd.R
import app.krewd.UiState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.krewd.data.ConfigOption
import app.krewd.data.Proposal
import app.krewd.data.SessionInfo
import app.krewd.proposalDraft
import app.krewd.sideDraft
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The new task, as a sheet over the Krewd tab: what to do, who does it (tap a face), one settings line, and
 * saved prompts. [initialEngine] is the agent whose face was tapped, else the last one used.
 *
 * The same sheet starts a side session of [parent] (from a chat's settings), and sends an agent's [proposal] as
 * the owner edits it: a new side session or sub-agent (with its agent and settings), or a message to another chat.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NewTaskSheet(
    state: UiState,
    viewModel: KrewdViewModel,
    initialEngine: String?,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    parent: SessionInfo? = null,
    proposal: Proposal? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }
    // Which draft the text, files and @ chats belong to: the new task's, a side session's, or a proposal's.
    val draftKey = proposal?.let { proposalDraft(it.id) } ?: parent?.let { sideDraft(it.id) } ?: NEW_DRAFT
    val plainTask = proposal == null && parent == null
    val promptOnly = proposal?.isPrompt == true
    // The chat this is about: the side session's parent, or the chat whose agent proposed it. It goes along by itself.
    val aboutId = parent?.id ?: proposal?.sessionId
    val aboutTitle = parent?.title ?: proposal?.let { p -> state.session(p.sessionId)?.title ?: p.sessionTitle } ?: "this chat"
    var returns by remember(draftKey) { mutableStateOf(proposal?.returns ?: false) }
    var title by remember(draftKey) { mutableStateOf(proposal?.title.orEmpty()) }
    var sending by remember { mutableStateOf(false) }
    var engineId by remember { mutableStateOf(proposal?.engine ?: initialEngine ?: viewModel.lastEngine()) }
    val engine = state.engines.firstOrNull { it.id == engineId } ?: state.engines.firstOrNull { it.isDefault } ?: state.engines.firstOrNull()
    val options = ConfigOption.ordered(viewModel.engineConfig(engine?.id))
    // Each agent starts with the owner's last picks for it (model, effort, permission mode, auto-approve).
    val saved = remember(engine?.id) { viewModel.engineDefaults(engine?.id) }
    var autoApprove by remember(engine?.id) { mutableStateOf(saved.autoApprove) }
    // Picks that differ from the engine's defaults; only these are sent.
    var picks by remember(engine?.id, options) {
        mutableStateOf(
            saved.config.filter { (id, value) ->
                val option = options.firstOrNull { it.id == id }
                option != null && value != option.current && option.choices.any { it.value == value }
            },
        )
    }
    var settingsOpen by remember { mutableStateOf(false) }
    val online = state.connection is Connection.Online
    // The new task's text lives in the view model (saved prompts set it too); a side session's or proposal's here,
    // kept on the phone under its own draft key.
    var ownText by remember(draftKey) {
        mutableStateOf(viewModel.messageDraft(draftKey).ifBlank { proposal?.text.orEmpty() })
    }
    val text = if (plainTask) state.newTaskText else ownText
    val setText: (String) -> Unit = {
        if (plainTask) viewModel.setNewTaskText(it) else {
            ownText = it
            viewModel.setMessageDraft(draftKey, it)
        }
    }
    // The field keeps its own cursor.
    var field by remember(draftKey) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
    val mention = if (field.selection.collapsed) mentionQuery(field.text, field.selection.start) else null
    val query = if (field.selection.collapsed && mention == null) commandQuery(field.text, field.selection.start) else null
    val matches = remember(engine?.commands, query) { matchCommands(engine?.commands.orEmpty(), query) }
    val chats = state.chatsOf(draftKey)
    if (mention != null && mention.typed.isNotEmpty()) LaunchedEffect(Unit) { viewModel.loadArchived() }
    val candidates = mention?.let { m ->
        mentionCandidates(state, aboutId, m.typed, chats.mapTo(HashSet()) { it.id } + listOfNotNull(aboutId, proposal?.targetId))
    }
    val files = state.draft(draftKey)
    val pickFiles = rememberFilePicker { viewModel.attach(draftKey, it) }
    val focus = remember { FocusRequester() }
    val canSend = online && !sending && (text.isNotBlank() || files.any { it.uploadId != null }) && files.none { it.uploading } &&
        (proposal == null || proposal.pending)
    val selected: (ConfigOption) -> String? = { picks[it.id] ?: it.current }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(online, engine?.id, options.isEmpty()) {
        // A new engine is probed in the background; ask again shortly so its choices appear.
        if (online && engine != null && options.isEmpty()) repeat(5) {
            delay(4_000)
            viewModel.refreshEngines()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().imePadding()) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp),
        ) {
            when (val c = state.connection) {
                is Connection.Offline -> Text("Can't reach Krewd: ${c.reason}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Connection.NotSetUp -> TextButton(onClick = { close(); onOpenSettings() }) { Text("Set up the connection first") }
                else -> {}
            }
            Text(
                when {
                    proposal != null -> "${proposal.sessionTitle ?: aboutTitle} proposes ${proposal.what}"
                    parent != null -> "Side session of $aboutTitle"
                    else -> "Ask Krewd"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
            if (!plainTask) {
                Text(
                    when {
                        promptOnly -> "It goes to that chat as a message from $aboutTitle, after its current turn."
                        else -> "A fresh chat. “$aboutTitle” is attached to its first message, so it can look things up there."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
            if (proposal != null && !proposal.pending) {
                Text(
                    "This proposal was ${if (proposal.status == "void") "for a chat that is gone" else proposal.status}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            if (proposal != null && !promptOnly) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(200) },
                    label = { Text("Title") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
            if (candidates != null) {
                ChatSuggestions(candidates, state.engines, onPick = {
                    field = removeMention(field, mention!!)
                    setText(field.text)
                    viewModel.addDraftChat(draftKey, it.ref())
                }, Modifier.padding(top = 6.dp))
            } else if (query != null) {
                CommandSuggestions(matches, onPick = {
                    field = applyCommand(field, query, it)
                    setText(field.text)
                }, Modifier.padding(top = 6.dp))
            }
            val colors = MaterialTheme.colorScheme
            val ink = colors.primary
            val commands = engine?.commands.orEmpty()
            val highlight = remember(commands, ink) { CommandHighlight(commands, ink) }
            val ranges = remember(field.text, commands) { commandRanges(field.text, commands) }
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    setText(it.text)
                },
                visualTransformation = highlight,
                onTextLayout = { layout = it },
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Medium,
                    lineHeight = 30.sp,
                    color = colors.onSurface,
                ),
                cursorBrush = SolidColor(colors.primary),
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                        if (field.text.isEmpty()) {
                            Text(
                                if (promptOnly) "The message" else if (plainTask) "What should Krewd do? @ adds a chat" else "What should it do?",
                                fontSize = 22.sp,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Box(Modifier.commandPills(layout, ranges, ink.copy(alpha = 0.16f))) { inner() }
                    }
                },
            )
            AttachmentTray(files, onRemove = { viewModel.removeAttachment(draftKey, it) }, modifier = Modifier.padding(vertical = 4.dp))
            DraftChatChips(chats, onRemove = { viewModel.removeDraftChat(draftKey, it) }, Modifier.padding(top = 4.dp))
            if (!plainTask) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(top = 6.dp)
                        .combinedClickable(onClick = { returns = !returns }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = returns, onCheckedChange = { returns = it })
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                promptOnly -> "Send its reply back to $aboutTitle"
                                proposal != null -> "Send the reply back to $aboutTitle"
                                else -> "Send replies back to $aboutTitle"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            if (returns) (if (promptOnly) "Its reply to this message comes back there by itself." else "A sub-agent: each reply it finishes comes back there by itself.")
                            else (if (promptOnly) "Nothing comes back." else "A side session: nothing comes back; it can be read from there when asked."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.engines.isNotEmpty() && !promptOnly) {
                SheetLabel("Who")
                CrewPicker(crewMembers(state), engine?.id, onPick = {
                    engineId = it
                    viewModel.setNewTaskEngine(it)
                })
                // Set up in Settings > Agents (its sign-in, or the profile it runs on); shown here only when it stops a task.
                engine?.problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                }
            }
            if (engine != null && !promptOnly) {
                SettingsLine(
                    settingsSummary(options, selected, autoApprove),
                    onClick = { settingsOpen = true },
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (plainTask) SheetLabel("Saved prompts", trailing = if (state.savedPrompts.isNotEmpty()) "Long-press to remove" else null)
            if (plainTask) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.savedPrompts.forEach { prompt ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.combinedClickable(
                            onClick = { viewModel.setNewTaskText(prompt) },
                            onLongClick = {
                                viewModel.forgetPrompt(prompt)
                                viewModel.toast("Removed the saved prompt")
                            },
                        ),
                    ) {
                        Text(
                            prompt,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
                val canSave = text.isNotBlank() && text.trim() !in state.savedPrompts
                Surface(
                    onClick = { viewModel.savePrompt(text) },
                    enabled = canSave,
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        "+ Save this",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (canSave) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                // A proposal is sent as text; files go with an ordinary message instead.
                if (proposal == null) {
                    FilledTonalIconButton(onClick = pickFiles, enabled = online, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_attach_24), contentDescription = "Attach files")
                    }
                } else if (proposal.pending) {
                    TextButton(onClick = { viewModel.dismissProposal(proposal); close() }) { Text("Dismiss") }
                }
                Spacer(Modifier.weight(1f))
                FilledIconButton(
                    onClick = {
                        when {
                            proposal != null -> {
                                sending = true
                                viewModel.sendProposal(proposal, text.trim(), title.trim(), engine?.id, picks, returns, draftKey) { _, error ->
                                    sending = false
                                    if (error == null) {
                                        viewModel.setMessageDraft(draftKey, "")
                                        close()
                                    } else {
                                        viewModel.toast(error)
                                    }
                                }
                            }
                            parent != null -> {
                                viewModel.newTask(text.trim(), autoApprove, engine?.id, picks, draft = draftKey, parentId = parent.id, returns = returns)
                                viewModel.setMessageDraft(draftKey, "")
                                close()
                            }
                            else -> {
                                viewModel.newTask(text.trim(), autoApprove, engine?.id, picks)
                                viewModel.setNewTaskText("")
                                close()
                            }
                        }
                    },
                    enabled = canSend,
                    shape = RoundedCornerShape(24.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_send_24),
                        contentDescription = when {
                            promptOnly -> "Send the message"
                            proposal != null || parent != null -> if (returns) "Start the sub-agent" else "Start the side session"
                            else -> "Start task"
                        },
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }

    if (settingsOpen && engine != null) {
        ModalBottomSheet(
            onDismissRequest = { settingsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp)) {
                Text("${engine.name} settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                Text(
                    "For this task. Krewd remembers your picks for ${engine.name}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
                if (options.isEmpty()) {
                    Text(
                        "${engine.name} has not listed its settings yet. They appear here once it has started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp, 16.dp),
                    )
                }
                AgentOptions(
                    options = options,
                    selected = selected,
                    enabled = online,
                    onPick = { option, value ->
                        picks = if (value == option.current) picks - option.id else picks + (option.id to value)
                        viewModel.rememberPick(engine.id, option.id, value)
                    },
                    autoApprove = autoApprove,
                    onAutoApprove = {
                        autoApprove = it
                        viewModel.rememberAutoApprove(engine.id, it)
                    },
                )
            }
        }
    }
}

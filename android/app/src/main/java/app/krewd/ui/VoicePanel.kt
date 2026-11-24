package app.krewd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.krewd.R
import app.krewd.data.VoiceLineItem
import app.krewd.data.VoiceMeta
import app.krewd.voice.Voice
import app.krewd.voice.VoiceChoice
import app.krewd.voice.VoiceSpeaker
import app.krewd.voice.VoiceUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The composer while the chat listens: who is talking and what is heard, the "My voice" and "Ask when unsure"
 * switches, the speakers (tap to rename or merge), a timed choice when Krewd is unsure whether a line needs a
 * reply, and Hold to instruct. Keyboard shows the normal composer again (listening goes on); Stop ends it.
 */
@Composable
fun VoicePanel(voice: VoiceUi, onKeyboard: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var speaker by remember { mutableStateOf<VoiceSpeaker?>(null) }
    var enrolling by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Voice.refreshStatus() }
    Column(modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dot = if (voice.speaking) colors.error else colors.error.copy(alpha = 0.45f)
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    voice.stopping -> "Sending the last words…"
                    voice.starting -> "Starting…"
                    voice.instructing -> "Instructing Krewd"
                    else -> "Listening"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.width(8.dp))
            Elapsed(voice.since)
            Spacer(Modifier.weight(1f))
            Level(voice.level)
            IconButton(onClick = onKeyboard) { Icon(painterResource(R.drawable.ic_keyboard_24), contentDescription = "Type instead") }
            IconButton(onClick = { Voice.stop(context) }, enabled = !voice.stopping) {
                Icon(painterResource(R.drawable.ic_stop_24), contentDescription = "Stop listening", tint = colors.error)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = voice.myVoice, onClick = { Voice.setMyVoice(context, !voice.myVoice) }, label = { Text("My voice") })
            FilterChip(selected = voice.ask, onClick = { Voice.setAsk(context, !voice.ask) }, label = { Text("Ask when unsure") })
            if (voice.enrolled == false) {
                AssistChip(onClick = { enrolling = true }, label = { Text("Set up My voice") })
            }
            voice.speakers.forEach { s ->
                AssistChip(onClick = { speaker = s }, label = { Text(s.label) })
            }
        }
        val choice = voice.choice
        if (choice != null) {
            ChoiceCard(choice)
        } else {
            Heard(voice)
        }
        HoldToInstruct(voice.instructing, enabled = !voice.stopping)
    }
    speaker?.let { s -> SpeakerDialog(s, voice.speakers.filter { it.id != s.id }, voice.enrolled == true, onClose = { speaker = null }) }
    if (enrolling) EnrollDialog(listening = voice.listening, onClose = { enrolling = false })
}

@Composable
private fun Elapsed(since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val s = ((now - since) / 1000).coerceAtLeast(0)
    Text("%d:%02d".format(s / 60, s % 60), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Level(level: Float) {
    val ink = MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 5) {
            val on = level * 5 > i
            Box(Modifier.width(3.dp).height((6 + i * 3).dp).background(if (on) ink else ink.copy(alpha = 0.2f), RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun Heard(voice: VoiceUi) {
    val colors = MaterialTheme.colorScheme
    val status = when {
        voice.holdUp == "offline" && voice.waiting > 0 -> "Offline · ${voice.waiting} waiting, sent when back"
        voice.holdUp == "server" && voice.waiting > 0 -> "Server busy or loading · ${voice.waiting} waiting"
        voice.waiting > 0 -> "Transcribing…"
        else -> null
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        when {
            voice.liveText.isNotBlank() -> Text(
                "${voice.liveLabel}: ${voice.liveText}…",
                style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            voice.lastLine != null -> Text(
                voice.lastLine, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            else -> Text(
                "Talk normally. Lines go to the chat with who said them; hold the button below to talk to Krewd.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
        }
        if (status != null) Text(status, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        voice.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.error) }
    }
}

/** Krewd is unsure whether a line needs a reply: two choices; the highlighted one is taken when time runs out. */
@Composable
private fun ChoiceCard(choice: VoiceChoice) {
    val colors = MaterialTheme.colorScheme
    var left by remember(choice.lineId) { mutableFloatStateOf(1f) }
    LaunchedEffect(choice.lineId) {
        while (true) {
            val ms = choice.until - android.os.SystemClock.elapsedRealtime()
            left = (ms / (choice.seconds * 1000f)).coerceIn(0f, 1f)
            if (ms <= 0) break
            delay(50)
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHighest) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${choice.speaker}: ${choice.text}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choice.options.forEach { o ->
                    FilterChip(selected = o.id == choice.defaultId, onClick = { Voice.choose(choice.lineId, o.id) }, label = { Text(o.label) })
                }
            }
            LinearProgressIndicator(progress = { left }, modifier = Modifier.fillMaxWidth().height(3.dp))
        }
    }
}

@Composable
private fun HoldToInstruct(down: Boolean, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (down) colors.primary else colors.secondaryContainer,
        contentColor = if (down) colors.onPrimary else colors.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().height(48.dp).pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(onPress = {
                Voice.instruct(true)
                try {
                    tryAwaitRelease()
                } finally {
                    Voice.instruct(false)
                }
            })
        },
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_mic_24), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (down) "Speak to Krewd, then let go" else "Hold to instruct Krewd", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SpeakerDialog(s: VoiceSpeaker, others: List<VoiceSpeaker>, enrolled: Boolean, onClose: () -> Unit) {
    var name by remember(s.id) { mutableStateOf(s.name.orEmpty()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true, label = { Text("Name") })
                if (others.isNotEmpty() || enrolled) {
                    Text("Same person as", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (enrolled) AssistChip(onClick = { Voice.merge(s.id, "me"); onClose() }, label = { Text("Me") })
                        others.forEach { o -> AssistChip(onClick = { Voice.merge(s.id, o.id); onClose() }, label = { Text(o.label) }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { Voice.rename(s.id, name.trim().ifBlank { null }); onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

private const val ENROLL_TEXT = "Read this aloud at your normal pace, the way you talk on a call. " +
    "Krewd listens to how you sound, not to the words. On a busy morning I check my messages, answer the " +
    "urgent ones first, and plan the rest of the day. When a customer calls, I listen to what they need, ask a " +
    "few questions, and explain the next steps clearly. If something is unclear, I say so and find out. " +
    "Numbers, names and dates matter, so I repeat them back: seven, fourteen, twenty-one, Monday and Friday."

/** Records about 30 s of the owner reading, for their voiceprint ("Me"); Cancel always closes it. */
@Composable
private fun EnrollDialog(listening: Boolean, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val close = { job?.cancel(); onClose() }
    var progress by remember { mutableFloatStateOf(-1f) }
    var level by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Set up My voice") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (listening) Text("Listening pauses while you record; nothing you read goes to the chat.", style = MaterialTheme.typography.bodySmall)
                Text(ENROLL_TEXT, style = MaterialTheme.typography.bodyMedium)
                if (progress >= 0f) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Level(level)
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Krewd keeps a short voice summary (256 numbers) on your server to tell your voice apart. No recording is kept.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = progress < 0f || result != null, onClick = {
                if (result != null) {
                    onClose()
                    return@TextButton
                }
                progress = 0f
                result = null
                job = scope.launch {
                    val out = Voice.enroll(30) { p, l -> progress = p; level = l }
                    result = out.fold(
                        onSuccess = { "Done: ${it.optDouble("speech_seconds", 0.0).toInt()} s of your voice heard." },
                        onFailure = { "Could not set it up: ${it.message}" },
                    )
                }
            }) { Text(if (result != null) "Close" else "Record 30 s") }
        },
        dismissButton = { if (result == null) TextButton(onClick = close) { Text("Cancel") } },
    )
}

/** A line heard that did not become a message, small and grey in the chat. */
@Composable
fun VoiceLineRow(item: VoiceLineItem) {
    val why = when (item.state) {
        "held" -> "no reply needed"
        "not_sent" -> "not sent · My voice is off"
        "echo" -> "reading the reply out"
        "failed" -> "could not be sent"
        else -> item.state
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
        Text(
            "${item.speaker} · $why: ${item.text}",
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}

/** At the top of a voice message's bubble: who said it and what it does ("P1 · A2", "P1 · adds to A1"). */
@Composable
fun VoiceHeader(meta: VoiceMeta, ink: Color) {
    var open by remember { mutableStateOf(false) }
    val what = when (meta.kind) {
        "instruct" -> "instruction"
        "add" -> "adds to ${meta.answer}"
        else -> meta.answer ?: "voice"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_mic_24), contentDescription = null, tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text("${meta.speaker} · $what", style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.8f))
            if (meta.meanwhile.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    if (open) "hide" else "+${meta.meanwhile.size} said meanwhile",
                    style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.7f),
                    modifier = Modifier.clickable { open = !open },
                )
            }
        }
        if (open) meta.meanwhile.forEach { (who, text) ->
            Text("$who: $text", style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.7f))
        }
    }
}

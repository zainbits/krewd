package app.krewd.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.krewd.R
import app.krewd.voice.Voice
import app.krewd.voice.VoiceUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The slim bar above the composer while the chat is in voice mode: Listening (or Muted) and the time, the "My voice"
 * switch, Mute / Unmute, Set up My voice while there is no voiceprint, and a short status. What is heard goes into
 * the composer below as `Me: ...` / `Others: ...` lines. Tapping the composer's mic again leaves voice mode.
 */
@Composable
fun VoiceBar(voice: VoiceUi, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var enrolling by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Voice.refreshStatus() }
    Column(modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dot = when {
                voice.muted -> colors.onSurfaceVariant.copy(alpha = 0.5f)
                voice.speaking -> colors.error
                else -> colors.error.copy(alpha = 0.45f)
            }
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(if (voice.muted) "Muted" else "Listening", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(6.dp))
            Elapsed(voice.since)
            if (!voice.muted) {
                Spacer(Modifier.width(8.dp))
                Level(voice.level)
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                FilterChip(selected = voice.myVoice, onClick = { Voice.setMyVoice(context, !voice.myVoice) }, label = { Text("My voice") })
                if (voice.enrolled == false) AssistChip(onClick = { enrolling = true }, label = { Text("Set up My voice") })
                AssistChip(
                    onClick = { Voice.setMuted(!voice.muted) },
                    enabled = !voice.stopping,
                    label = { Text(if (voice.muted) "Unmute" else "Mute") },
                    leadingIcon = {
                        Icon(painterResource(if (voice.muted) R.drawable.ic_mic_24 else R.drawable.ic_mic_off_24),
                            contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )
            }
        }
        val status = when {
            voice.error != null -> voice.error
            voice.stopping -> "Leaving voice mode · adding the last words…"
            voice.settling -> "Adding the last words before sending…"
            voice.holdUp == "offline" -> if (voice.waiting > 0) "Offline · ${voice.waiting} waiting" else "Offline"
            voice.holdUp == "server" && voice.waiting > 0 -> "Server busy or loading · ${voice.waiting} waiting"
            voice.starting -> "Starting…"
            voice.waiting > 0 -> "Transcribing…"
            else -> null
        }
        if (status != null) {
            Text(
                status, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (voice.error != null) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
    if (enrolling) EnrollDialog(listening = voice.listening && !voice.muted, onClose = { enrolling = false })
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
                if (listening) Text("Nothing you read goes into the message.", style = MaterialTheme.typography.bodySmall)
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

/** At the top of a message sent from voice mode: a small mic. */
@Composable
fun VoiceMark(ink: Color) {
    Icon(painterResource(R.drawable.ic_mic_24), contentDescription = "Sent from voice mode", tint = ink.copy(alpha = 0.6f),
        modifier = Modifier.size(13.dp))
}

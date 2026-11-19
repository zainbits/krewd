package app.krewd.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first

/**
 * The reply that is streaming in right now, and what to call when it grows on screen by some pixels. Provided by
 * the chat: while you read above the bottom, it scrolls by the same amount, so the text you are on stays put.
 */
class LiveReply(val key: Any?, val grew: (key: Any, px: Int) -> Unit)

val LocalLiveReply = staticCompositionLocalOf { LiveReply(null) { _, _ -> } }

/**
 * Streamed text as shown: how much of it, and how faded in each of its last characters is. [fade] takes a
 * character's distance from the end (0 is the last one) and gives its opacity; null when nothing is fading.
 */
class SmoothText(val text: String, val fade: ((Int) -> Float)?)

private const val FADE_NS = 380_000_000L

/**
 * Streamed text let out like a breeze: the agent's chunks arrive in bursts, so the text is let out character by
 * character at an even pace (fast enough to empty what is waiting in about the time between chunks), and each new
 * character fades in over a third of a second. Between chunks, with nothing fading, it sleeps (no frames, no
 * battery). Text that is not live (history, a finished reply) shows in full at once.
 */
@Composable
fun rememberSmoothText(key: Any, text: String, live: Boolean): SmoothText {
    // A chat opened in the middle of a long reply shows what is there; a reply that starts now is let out.
    var shown by remember(key) { mutableStateOf(if (live && text.length < 200) 0 else text.length) }
    var fade by remember(key) { mutableStateOf<((Int) -> Float)?>(null) }
    val target by rememberUpdatedState(text)
    if (!live && shown != text.length) shown = text.length
    if (!live && fade != null) fade = null
    LaunchedEffect(key, live) {
        if (!live) return@LaunchedEffect
        var pos = shown.toDouble()
        var seenLength = target.length
        var lastChunk = System.nanoTime()
        var gap = 0.3 // seconds between chunks, smoothed
        var lastFrame = 0L
        // When each run of characters came out: (end index, time), oldest first.
        val steps = ArrayDeque<Pair<Int, Long>>()
        var opaque = shown // characters before this have finished fading in
        while (true) {
            if (shown >= target.length && steps.isEmpty()) {
                // Caught up and nothing fading: sleep until the next chunk instead of waking every frame.
                fade = null
                snapshotFlow { target.length }.first { it > shown }
                lastFrame = 0L
            }
            withFrameNanos { now ->
                val text = target
                if (text.length > seenLength) {
                    val since = (now - lastChunk) / 1e9
                    gap = gap * 0.7 + since.coerceIn(0.05, 1.0) * 0.3
                    lastChunk = now
                }
                seenLength = text.length
                if (pos > text.length) pos = text.length.toDouble()
                val dt = if (lastFrame == 0L) 1.0 / 60 else (now - lastFrame) / 1e9
                lastFrame = now
                val waiting = text.length - pos
                if (waiting > 0) {
                    val speed = maxOf(waiting / gap.coerceIn(0.12, 0.8), 30.0) // characters per second
                    pos = minOf(text.length.toDouble(), pos + speed * dt)
                }
                val end = pos.toInt().coerceAtMost(text.length)
                if (end > shown) steps.addLast(end to now)
                while (steps.isNotEmpty() && now - steps.first().second >= FADE_NS) opaque = steps.removeFirst().first
                shown = end
                val runs = steps.toList()
                val solid = opaque
                fade = if (runs.isEmpty()) null else fade@{ fromEnd ->
                    val index = end - 1 - fromEnd
                    if (index < solid) return@fade 1f
                    // The first run that reaches past this character is the one that let it out.
                    val at = runs.firstOrNull { it.first > index }?.second
                    if (at == null) 1f else ((now - at).toFloat() / FADE_NS).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
                }
            }
        }
    }
    return SmoothText(if (shown >= text.length) text else text.substring(0, shown), fade)
}

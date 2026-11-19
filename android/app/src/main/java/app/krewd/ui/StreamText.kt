package app.krewd.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos

/**
 * The reply that is streaming in right now, and what to call just before it grows on screen. Provided by the
 * chat: [hold] keeps the text you are reading in place when you have scrolled up from the bottom.
 */
class LiveReply(val key: Any?, val hold: (Any) -> Unit)

val LocalLiveReply = staticCompositionLocalOf { LiveReply(null, {}) }

/**
 * Streamed text as it should be shown: the agent's chunks arrive in bursts, so the text is let out at an even
 * pace instead, whole words at a time, fast enough to empty what is waiting in about the time between chunks.
 * Text that is not live (history, a finished reply) shows in full at once.
 */
@Composable
fun rememberSmoothText(key: Any, text: String, live: Boolean, onGrow: () -> Unit): String {
    // A chat opened in the middle of a long reply shows what is there; a reply that starts now is let out.
    var shown by remember(key) { mutableStateOf(if (live && text.length < 200) 0 else text.length) }
    val target by rememberUpdatedState(text)
    val grow by rememberUpdatedState(onGrow)
    if (!live && shown != text.length) {
        // The turn ended (or the text was replaced): no more pacing.
        if (shown < text.length) grow()
        shown = text.length
    }
    LaunchedEffect(key, live) {
        if (!live) return@LaunchedEffect
        var pos = shown.toDouble()
        var seenLength = target.length
        var lastChunk = System.nanoTime()
        var gap = 0.3 // seconds between chunks, smoothed
        var lastFrame = 0L
        var lastShow = 0L
        while (true) {
            withFrameNanos { now ->
                val text = target
                if (text.length != seenLength) {
                    if (text.length > seenLength) {
                        val since = (now - lastChunk) / 1e9
                        gap = (gap * 0.7 + since.coerceIn(0.05, 1.0) * 0.3)
                        lastChunk = now
                    }
                    seenLength = text.length
                }
                if (pos > text.length) pos = text.length.toDouble()
                val dt = if (lastFrame == 0L) 0.0 else (now - lastFrame) / 1e9
                lastFrame = now
                val waiting = text.length - pos
                if (waiting > 0) {
                    val speed = maxOf(waiting / gap.coerceIn(0.1, 0.8), 40.0) // characters per second
                    pos = minOf(text.length.toDouble(), pos + speed * dt)
                }
                // Whole words: run on to the next space, so a word never shows half typed.
                var end = pos.toInt()
                while (end < text.length && !text[end].isWhitespace()) end++
                // About 30 updates a second is smooth to the eye and keeps markdown work low on long replies.
                if (end != shown && (now - lastShow >= 30_000_000L || end == text.length)) {
                    if (end > shown) grow()
                    shown = end
                    lastShow = now
                }
            }
        }
    }
    return if (shown >= text.length) text else text.substring(0, shown)
}

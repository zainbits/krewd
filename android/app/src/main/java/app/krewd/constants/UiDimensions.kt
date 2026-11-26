package app.krewd.constants

import androidx.compose.ui.unit.dp

/** Floating title pill, composer and content clearance for the edge-to-edge shell. */
object ShellDimensions {
    val TITLE_PILL_HEIGHT = 48.dp
    val TITLE_PILL_HORIZONTAL_PADDING = 20.dp
    val TITLE_PILL_START_MARGIN = 12.dp
    val TITLE_PILL_TOP_GAP = 8.dp
    val PILL_BORDER_WIDTH = 1.dp
    val CONTENT_HORIZONTAL_PADDING = 16.dp

    /** Extra space so the first and last items can scroll fully clear of the pills. */
    val CONTENT_CLEARANCE = 16.dp
    val COMPOSER_MIN_HEIGHT = 56.dp
    val COMPOSER_MARGIN = 12.dp
    /** The agent and settings chips above the composer. */
    val AGENT_CHIPS_HEIGHT = 40.dp
    val CARD_CORNER = 20.dp
}

object Timing {
    const val LONG_POLL_SECONDS = 25
    /** How long the live connection stays after the app leaves the screen (an app switch), before it rests. */
    const val BACKGROUND_GRACE_MS = 20_000L
    /** How much later than the server's wait a long poll may answer before its connection counts as dead. */
    const val READ_MARGIN_MS = 6_000
    const val CONNECT_TIMEOUT_MS = 4_000
    /** A home address answers within this or is not on this network. */
    const val HOME_PROBE_MS = 2_000
    /** When another route answers first, a home address still wins if it answers within this. */
    const val HOME_GRACE_MS = 400L
    /** The whole race of the addresses, name lookups included. */
    const val RACE_LIMIT_MS = 8_000L
    /** While on another route in the foreground, how often the home address is tried again. */
    const val HOME_CHECK_MS = 30_000L
    /** A drop this short shows as Connecting, not Offline. */
    const val QUIET_RECONNECT_MS = 8_000L
    /** Waits between reconnects (plus up to 25% jitter); the first retry is at once. */
    val RETRY_DELAYS_MS = longArrayOf(0, 1_000, 2_000, 5_000, 10_000, 20_000)
    /** Waits between tries of one message or file upload (plus jitter). */
    val SEND_RETRY_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

    /** The [attempt]th wait of [delays] (the last one repeats), with up to 25% random jitter added. */
    fun backoff(delays: LongArray, attempt: Int): Long {
        val base = delays[minOf(attempt, delays.lastIndex)]
        return base + (base * kotlin.random.Random.nextDouble(0.0, 0.25)).toLong()
    }
}

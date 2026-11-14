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

object LiquidNavDimensions {
    val BAR_HEIGHT = 64.dp
    val SELECTED_PILL_WIDTH = 64.dp
    val GROUP_GAP = 10.dp
    val SIDE_MARGIN = 12.dp
    val BOTTOM_GAP = 8.dp
    val BORDER_WIDTH = 1.dp

    /** Outer bottom corner radius when the display reports no rounded corners (emulators, square screens). */
    val FALLBACK_OUTER_CORNER_RADIUS = 32.dp
    val ICON_SIZE = 24.dp
    val ICON_LABEL_GAP = 2.dp
    val RIPPLE_RADIUS = 28.dp
    const val LABEL_SELECTED_SCALE = 0.85f
}

object Timing {
    const val LONG_POLL_SECONDS = 25
    const val CONNECT_TIMEOUT_MS = 4_000
    const val READ_MARGIN_MS = 10_000
    val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 20_000)
}

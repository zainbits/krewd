package app.krewd.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Call with a row's key just before that row opens or closes. Provided by the chat; a no-op elsewhere.
 */
val LocalHoldRow = staticCompositionLocalOf<(Any) -> Unit> { {} }

/**
 * Keeps a row's top edge where it is while it opens or closes, so the row you tapped stays under your finger
 * and grows or shrinks below it.
 *
 * The chat list is laid out bottom-up (reverseLayout) and keeps its place by its lowest visible row. An open
 * row that runs past the bottom of the screen becomes that row, so closing it threw the list far up the chat,
 * and opening one pushed its own header off the top. Anchoring the list on the row just above (the next index,
 * since the list is reversed) at its current place, in the same frame as the toggle, pins the tapped row's top.
 */
fun LazyListState.holdRow(key: Any) {
    val info = layoutInfo
    val row = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
    if (row.index + 1 >= info.totalItemsCount) return
    // Where the next row up starts, in the list's own scroll coordinates (offsets grow up the screen here).
    val aboveStart = row.offset + row.size + info.mainAxisItemSpacing
    requestScrollToItem(row.index + 1, -aboveStart)
}

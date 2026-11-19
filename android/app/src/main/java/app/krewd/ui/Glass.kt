package app.krewd.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the liquid glass (Vitreous on the chat's title bar and composer, Meniscus on the nav bar) and
 * its stretch are drawn. [on] is the Settings switch unless [throttled]: battery saver or a hot phone
 * turn the glass off, and Settings then shows the switch off and greyed out.
 */
@Immutable
data class KrewdGlass(val on: Boolean, val throttled: Boolean)

val LocalKrewdGlass = compositionLocalOf { KrewdGlass(on = false, throttled = false) }

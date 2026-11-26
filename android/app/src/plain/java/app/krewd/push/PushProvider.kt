package app.krewd.push

import android.content.Context

/** The plain flavor has no Google push: alerts arrive only while the app is open (its live connection). */
object PushProvider {
    const val NAME = "none (plain build)"
    val built: Boolean get() = false

    fun init(context: Context): Boolean = false

    suspend fun token(context: Context): String? = null
}

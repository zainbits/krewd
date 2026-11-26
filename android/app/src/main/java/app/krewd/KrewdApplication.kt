package app.krewd

import android.app.Application
import app.krewd.push.AlertCenter
import app.krewd.push.PushProvider

/** Holds the one [KrewdViewModel] for the whole process, so its connection outlives any screen. */
class KrewdApplication : Application() {
    val krewd: KrewdViewModel by lazy { KrewdViewModel(this) }

    override fun onCreate() {
        super.onCreate()
        AlertCenter.channels(this)
        AlertCenter.restore(this)
        // Push starts with the process: a push can be what started it (the google flavor; nothing in plain).
        PushProvider.init(this)
    }
}

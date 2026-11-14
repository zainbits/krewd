package app.krewd

import android.app.Application

/** Holds the one [KrewdViewModel] for the whole process, so its connection outlives any screen. */
class KrewdApplication : Application() {
    val krewd: KrewdViewModel by lazy { KrewdViewModel(this) }
}

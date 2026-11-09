package app.crew

import android.app.Application

/** Holds the one [CrewViewModel] for the whole process, so its connection outlives any screen. */
class CrewApplication : Application() {
    val crew: CrewViewModel by lazy { CrewViewModel(this) }
}

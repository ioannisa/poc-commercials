package eu.anifantakis.commercials.feature.timetable.data

import eu.anifantakis.commercials.feature.timetable.domain.TimetablePreferences
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.invoke

/**
 * [TimetablePreferences] over KSafe (the same encrypted store as the
 * session). Persistent storage only - the grid ViewModel keeps the live
 * value in its own state, so no Compose-observable mirror is needed here.
 * Koin singleton.
 */
class KSafeTimetablePreferences(private val ksafe: KSafe) : TimetablePreferences {
    // pre-refactor key preserved - existing installations keep their choice
    override var showSpotTimes: Boolean by ksafe(false, key = "grid_show_spot_times")
}

package eu.anifantakis.commercials.feature.timetable.domain

/**
 * Persisted timetable DISPLAY preferences - feature-local, not the app-wide
 * [eu.anifantakis.commercials.feature.preferences.domain.UserPreferences].
 * A narrow interface (unification pattern, testing.md) so the grid ViewModel
 * depends on THIS, not on a concrete KSafe it cannot fake in commonTest.
 */
interface TimetablePreferences {
    /** Grid cells show spot COUNT or the summed spot TIME (legacy '#' toggle). */
    var showSpotTimes: Boolean
}

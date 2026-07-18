package eu.anifantakis.commercials.core.domain.context

/**
 * What the user is LOOKING AT right now, as a short machine-readable line -
 * the active screen publishes it (the timetable: month, view mode, selected
 * cell), the AI assistant reads it at send time and ships it with the
 * request, so "τι βλέπω;"/"σε αυτό το διάλειμμα" resolve against the actual
 * screen instead of a guess.
 *
 * A plain last-writer-wins holder, not a flow: nothing reacts to it - it is
 * sampled exactly once per chat request. Koin singleton.
 */
class ActiveScreenContext {
    var current: String? = null
}

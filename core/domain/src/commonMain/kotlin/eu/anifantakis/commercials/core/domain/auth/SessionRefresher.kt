package eu.anifantakis.commercials.core.domain.auth

/**
 * Re-reads the server's answer about WHO THE USER IS - above all, which stations
 * they may access - and folds it into the live [UserSession].
 *
 * The keep-alive already does this on every beat, but its beat is paced by the
 * TOKEN's lifetime: a three-day session knocks every six hours. That is exactly
 * right for keeping a token alive and hopelessly wrong as a way to notice a
 * station that appeared thirty seconds ago - which is why a freshly migrated
 * group still took an app restart to show up in the dropdown (a restart knocks
 * immediately) even after the knock started carrying the list.
 *
 * So the code that KNOWS the hosted stations just changed says so, instead of
 * everyone waiting for the next beat. Polling faster would be the wrong fix: it
 * would cost every idle client a request a minute, forever, to catch an event
 * that happens a handful of times in a database's life.
 */
fun interface SessionRefresher {
    /** Never throws: a refresh that cannot reach the server changes nothing. */
    suspend fun refresh()
}

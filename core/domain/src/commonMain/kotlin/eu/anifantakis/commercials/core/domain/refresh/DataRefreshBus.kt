package eu.anifantakis.commercials.core.domain.refresh

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * App-wide "the data changed UNDER you" signal. Screens normally own their
 * refetches, but some writers live OUTSIDE the screen showing the data - the
 * AI assistant approving a schedule mutation is the first: the timetable must
 * reload so the user watches the change land live, without a station switch
 * or a restart.
 *
 * Writers call [notifyChanged] after a successful mutation; data screens
 * collect [events] and refetch. Deliberately payload-free: a refetch is cheap
 * and always correct, while a typed "what changed" contract would couple
 * every writer to every reader's caching scheme. Koin singleton.
 */
class DataRefreshBus {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val events: SharedFlow<Unit> = _events

    fun notifyChanged() {
        _events.tryEmit(Unit)
    }
}

package eu.anifantakis.commercials.feature.timetable.domain

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Write side of the grid - the 'a'/'r' keys and the detail screen's
 * reordering. Placements are REAL database rows on the server.
 */
interface PlacementsRepository {

    /**
     * Appends the spot at the end of the (time, date) cell.
     *
     * [time] need not be a break that already exists: airing a spot at 23:55 is
     * what BRINGS the 23:55 break into being (the legacy console's "Πρόσθεση
     * νέου διαλείμματος - Ώρα:" box did exactly this).
     */
    suspend fun add(spotId: Long, time: LocalTime, date: LocalDate): DataResult<PlacedCommercial, DataError.Network>

    suspend fun remove(placementId: Long): EmptyDataResult<DataError.Network>

    /** Persists a cell's ordering; list indexes become positions. */
    suspend fun reorder(time: LocalTime, date: LocalDate, orderedIds: List<Long>): EmptyDataResult<DataError.Network>
}

package eu.anifantakis.commercials.feature.timetable.domain

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import kotlinx.datetime.LocalDate

/**
 * Write side of the grid - the 'a'/'r' keys and the detail screen's
 * reordering. Placements are REAL database rows on the server.
 */
interface PlacementsRepository {

    /** Appends the spot at the end of the (break, date) cell. */
    suspend fun add(spotId: Long, breakId: Long, date: LocalDate): DataResult<PlacedCommercial, DataError.Network>

    suspend fun remove(placementId: Long): EmptyDataResult<DataError.Network>

    /** Persists a cell's ordering; list indexes become positions. */
    suspend fun reorder(breakId: Long, date: LocalDate, orderedIds: List<Long>): EmptyDataResult<DataError.Network>
}

package eu.anifantakis.commercials.feature.timetable.domain

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Write side of the grid - the 'a'/'r' keys, the detail screen's reordering,
 * and break creation. Placements AND breaks are real database rows on the
 * server; a break owns its slot's programme.
 */
interface PlacementsRepository {

    /**
     * Appends the spot at the end of the (time, date) cell.
     *
     * [time] need not be a break that already exists - airing a spot at 23:55
     * brings the 23:55 break into being. [programId] (the operator's selected
     * "Τύπος Προγράμματος") matters only for a WHITE cell - no break there, or
     * an unpainted one: the FIRST spot paints the break with it. A painted
     * break ignores [programId]: the spot inherits the break's own programme,
     * exactly like the legacy console. White cell + no programme -> the server
     * refuses (409).
     */
    suspend fun add(
        spotId: Long,
        time: LocalTime,
        date: LocalDate,
        programId: Long? = null,
    ): DataResult<PlacedCommercial, DataError.Network>

    suspend fun remove(placementId: Long): EmptyDataResult<DataError.Network>

    /** Persists a cell's ordering; list indexes become positions. */
    suspend fun reorder(time: LocalTime, date: LocalDate, orderedIds: List<Long>): EmptyDataResult<DataError.Network>

    /**
     * Creates an EMPTY, UNPAINTED break at (time, date) - the legacy console's
     * "Πρόσθεση νέου διαλείμματος: Ώρα" box. It only holds a ROW on the grid;
     * its cells stay white until a first spot (with a selected programme)
     * paints each one.
     */
    suspend fun createBreak(time: LocalTime, date: LocalDate): EmptyDataResult<DataError.Network>
}

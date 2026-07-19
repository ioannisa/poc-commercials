package eu.anifantakis.commercials.feature.timetable.domain

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.feature.timetable.domain.model.Program

/**
 * The station's programme catalog - the legacy console's "Τύποι Προγράμματος"
 * box: the dropdown plus its ΔΙΟΡΘ / ΠΡΟΣΘ / ΑΦΑΙΡ / Χρώμα buttons. Staff-only
 * (the server gates it the same way as placement editing).
 */
interface ProgramsRepository {

    /** The visible programmes, sorted by name - the dropdown's content. */
    suspend fun list(): DataResult<List<Program>, DataError.Network>

    /** ΠΡΟΣΘ: create a programme; returns it with its server id. */
    suspend fun create(name: String, colorArgb: Int?): DataResult<Program, DataError.Network>

    /** ΔΙΟΡΘ / Χρώμα: rename and/or recolor - nulls keep the current value. */
    suspend fun update(id: Long, name: String? = null, colorArgb: Int? = null): EmptyDataResult<DataError.Network>

    /** ΑΦΑΙΡ: retire from the dropdown (soft delete - painted history keeps its colours). */
    suspend fun remove(id: Long): EmptyDataResult<DataError.Network>
}

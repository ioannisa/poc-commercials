package eu.anifantakis.commercials.feature.timetable.domain

import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot

/**
 * The finder console's drill-down (party -> contract lines -> spots). The
 * party SEARCH itself lives in core (PartySearchRepository) because the
 * schedule-email feature drives the same search.
 */
interface FinderRepository {
    suspend fun contractLines(clientCode: String, kind: PartyKind): DataResult<List<ContractLine>, DataError.Network>
    suspend fun lineSpots(lineId: Long): DataResult<List<ContractLineSpot>, DataError.Network>
}

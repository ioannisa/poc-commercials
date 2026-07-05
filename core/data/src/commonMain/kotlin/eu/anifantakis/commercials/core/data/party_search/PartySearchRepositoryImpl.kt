package eu.anifantakis.commercials.core.data.party_search

import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.party_search.PartySearchRepository
import eu.anifantakis.commercials.core.domain.party_search.data_source.RemotePartySearchDataSource
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult

/** Organizer only: all I/O lives in [RemotePartySearchDataSource]. */
class PartySearchRepositoryImpl(
    private val remoteDataSource: RemotePartySearchDataSource,
) : PartySearchRepository {

    override suspend fun search(query: String, kind: PartyKind): DataResult<List<Party>, DataError.Network> =
        remoteDataSource.search(query, kind)
}

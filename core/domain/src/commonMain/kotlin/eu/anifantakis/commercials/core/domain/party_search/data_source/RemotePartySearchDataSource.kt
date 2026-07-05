package eu.anifantakis.commercials.core.domain.party_search.data_source

import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult

/** Network side of party search - the only class that touches the HTTP client. */
interface RemotePartySearchDataSource {
    suspend fun search(query: String, kind: PartyKind): DataResult<List<Party>, DataError.Network>
}

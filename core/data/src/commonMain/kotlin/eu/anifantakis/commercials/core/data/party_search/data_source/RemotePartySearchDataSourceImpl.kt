package eu.anifantakis.commercials.core.data.party_search.data_source

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.party_search.data_source.RemotePartySearchDataSource
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.map
import kotlinx.serialization.Serializable

@Serializable
private data class PartyDto(
    val code: String,
    val name: String,
    val email: String? = null,
    val spotCount: Int = 0,
    val placementCount: Int = 0,
    val vatNumber: String? = null,
    val phone: String? = null,
)

private fun PartyDto.toDomain(): Party = Party(
    code = code,
    name = name,
    email = email,
    vatNumber = vatNumber,
    phone = phone,
    spotCount = spotCount,
    placementCount = placementCount,
)

class RemotePartySearchDataSourceImpl(private val api: ApiHttpClient) : RemotePartySearchDataSource {

    override suspend fun search(query: String, kind: PartyKind): DataResult<List<Party>, DataError.Network> =
        api.get<List<PartyDto>>("/api/parties/search", "kind" to kind.wire, "q" to query)
            .map { list -> list.map { it.toDomain() } }
}

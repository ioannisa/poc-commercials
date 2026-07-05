package eu.anifantakis.commercials.core.data.party_search.data_source

import eu.anifantakis.commercials.core.data.config.AppConfig
import eu.anifantakis.commercials.core.data.network.authenticatedJsonClient
import eu.anifantakis.commercials.core.data.network.dataCall
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.party_search.data_source.RemotePartySearchDataSource
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.map
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.encodeURLParameter
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

class RemotePartySearchDataSourceImpl(private val session: AuthSession) : RemotePartySearchDataSource {

    private val httpClient by lazy { authenticatedJsonClient(session) }

    override suspend fun search(query: String, kind: PartyKind): DataResult<List<Party>, DataError.Network> =
        dataCall {
            val station = session.selectedStation?.id ?: ""
            httpClient.get(
                "${AppConfig.require().serverBaseUrl}/api/parties/search" +
                    "?station=$station&kind=${kind.wire}&q=${query.encodeURLParameter()}"
            ).body<List<PartyDto>>()
        }.map { list -> list.map { it.toDomain() } }
}

package eu.anifantakis.commercials.finder

import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.data.network.authenticatedJsonClient
import eu.anifantakis.commercials.core.data.config.AppConfig
import eu.anifantakis.commercials.email.PartyKind
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** A contract line ("product") - ERP identity pending, stats computed. */
@Serializable
data class FinderContractLine(
    val lineId: Long,
    val contractNumber: String,
    val isGift: Boolean,
    val lineNo: Int,
    val desiredQty: Int,
    val spotCount: Int,
    val placements: Int,
    val totalSeconds: Long,
    val entryDate: String? = null,
)

@Serializable
data class FinderSpot(
    val spotId: Long,
    val description: String,
    val durationSeconds: Int,
    val placements: Int,
    /** Aired seconds - Αναλωμένα Secs in the legacy console. */
    val totalSeconds: Long = 0,
)

/**
 * The placement the server just created - same shape as the month grid's
 * commercials; [id] is the placement id (what remove deletes).
 */
@Serializable
data class AddedCommercial(
    val id: Long,
    val position: Int,
    val clientCode: String,
    val clientName: String,
    val message: String,
    val durationSeconds: Int,
    val type: String,
    val contract: String,
    val flow: String,
)

@Serializable
private data class AddPlacementRequest(val spotId: Long, val breakId: Long, val date: String)

@Serializable
private data class ReorderPlacementsRequest(val breakId: Long, val date: String, val orderedIds: List<Long>)

/**
 * Client for the spot finder (the legacy "Εύρεση" Details Console) and the
 * grid's real add/remove: party -> contract lines -> spots, then placements
 * are INSERTed/DELETEd in the station database. Koin singleton.
 */
class SpotFinderApi(private val session: AuthSession) {

    private val httpClient by lazy { authenticatedJsonClient(session) }

    private fun base(path: String): String {
        val station = session.selectedStation?.id ?: ""
        return "${AppConfig.require().serverBaseUrl}/api/$path?station=$station"
    }

    suspend fun contracts(clientCode: String, kind: PartyKind): Result<List<FinderContractLine>> = runCatching {
        httpClient.get(base("finder/contracts") + "&kind=${kind.wire}&clientCode=$clientCode").body()
    }

    suspend fun spots(lineId: Long): Result<List<FinderSpot>> = runCatching {
        httpClient.get(base("finder/spots") + "&lineId=$lineId").body()
    }

    suspend fun addPlacement(spotId: Long, breakId: Long, date: LocalDate): Result<AddedCommercial> = runCatching {
        httpClient.post(base("schedule/placements")) {
            contentType(ContentType.Application.Json)
            setBody(AddPlacementRequest(spotId, breakId, date.toString()))
        }.body()
    }

    suspend fun removePlacement(placementId: Long): Result<Unit> = runCatching {
        val resp = httpClient.delete(base("schedule/placements/$placementId"))
        if (!resp.status.isSuccess()) error("Remove failed: ${resp.status}")
    }

    /** Persists the break-detail screen's ordering; indexes become positions. */
    suspend fun reorderPlacements(breakId: Long, date: LocalDate, orderedIds: List<Long>): Result<Unit> = runCatching {
        val resp = httpClient.put(base("schedule/placements/order")) {
            contentType(ContentType.Application.Json)
            setBody(ReorderPlacementsRequest(breakId, date.toString(), orderedIds))
        }
        if (!resp.status.isSuccess()) error("Reorder failed: ${resp.status}")
    }
}

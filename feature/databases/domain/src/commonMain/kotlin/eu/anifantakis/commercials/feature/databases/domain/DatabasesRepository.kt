package eu.anifantakis.commercials.feature.databases.domain

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError

/** One hosted station database, as listed on the Databases screen. */
data class HostedStation(
    val id: String,
    val name: String,
    val database: String,
    val reachable: Boolean = true,
    val placements: Long? = null,
    val dateRange: String? = null,
)

data class StationDeletion(
    val status: String,
    val grantsRemoved: Int,
    val yamlEntryRemoved: Boolean,
    val databaseDropped: Boolean,
)

/** Super-admin management of hosted station databases. */
interface DatabasesRepository {

    suspend fun listStations(): DataResult<List<HostedStation>, RemoteError>

    /**
     * mode "safe": unhost only (yaml entry, user grants, live registry).
     * mode "hard": additionally DROP the schema on its MySQL server.
     * [confirmId] must repeat the station id - typed confirmation.
     */
    suspend fun deleteStation(id: String, hard: Boolean, confirmId: String): DataResult<StationDeletion, RemoteError>
}

package eu.anifantakis.commercials.feature.databases.domain.data_source

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.feature.databases.domain.*

/**
 * Network side of the feature - the only class that touches the HTTP
 * client. Same surface as the repository: the repository is the organizer,
 * this is the wire.
 */
interface RemoteDatabasesDataSource {

    suspend fun listStations(): DataResult<List<HostedStation>, RemoteError>

    /**
     * mode "safe": unhost only (yaml entry, user grants, live registry).
     * mode "hard": additionally DROP the schema on its MySQL server.
     * [confirmId] must repeat the station id - typed confirmation.
     */
    suspend fun deleteStation(id: String, hard: Boolean, confirmId: String): DataResult<StationDeletion, RemoteError>
}

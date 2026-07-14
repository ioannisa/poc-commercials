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
     * See [DeleteMode]. [confirmId] is the typed confirmation - the STATION id,
     * except for [DeleteMode.DROP_GROUP], which takes the GROUP id.
     */
    suspend fun deleteStation(
        id: String,
        mode: DeleteMode,
        confirmId: String,
    ): DataResult<StationDeletion, RemoteError>
}

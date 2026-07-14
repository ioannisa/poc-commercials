package eu.anifantakis.commercials.feature.databases.data

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.feature.databases.domain.DatabasesRepository
import eu.anifantakis.commercials.feature.databases.domain.DeleteMode
import eu.anifantakis.commercials.feature.databases.domain.HostedStation
import eu.anifantakis.commercials.feature.databases.domain.StationDeletion
import eu.anifantakis.commercials.feature.databases.domain.data_source.RemoteDatabasesDataSource

/** Organizer only: all I/O lives in [RemoteDatabasesDataSource]. */
class DatabasesRepositoryImpl(
    private val remoteDataSource: RemoteDatabasesDataSource,
) : DatabasesRepository {

    override suspend fun listStations(): DataResult<List<HostedStation>, RemoteError> =
        remoteDataSource.listStations()

    override suspend fun deleteStation(
        id: String,
        mode: DeleteMode,
        confirmId: String,
    ): DataResult<StationDeletion, RemoteError> = remoteDataSource.deleteStation(id, mode, confirmId)
}

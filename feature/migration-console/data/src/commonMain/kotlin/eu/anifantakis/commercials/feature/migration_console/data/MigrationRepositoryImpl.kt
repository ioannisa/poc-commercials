package eu.anifantakis.commercials.feature.migration_console.data

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.feature.migration_console.domain.BrowseListing
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationFlowChoice
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationRepository
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationStart
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationStatus
import eu.anifantakis.commercials.feature.migration_console.domain.data_source.RemoteMigrationDataSource

/** Organizer only: all I/O lives in [RemoteMigrationDataSource]. */
class MigrationRepositoryImpl(
    private val remoteDataSource: RemoteMigrationDataSource,
) : MigrationRepository {

    override suspend fun status(): DataResult<MigrationStatus, RemoteError> =
        remoteDataSource.status()

    override suspend fun start(request: MigrationStart): DataResult<MigrationStatus, RemoteError> =
        remoteDataSource.start(request)

    override suspend fun chooseFlow(choice: MigrationFlowChoice): DataResult<MigrationStatus, RemoteError> =
        remoteDataSource.chooseFlow(choice)

    override suspend fun reset(): DataResult<MigrationStatus, RemoteError> =
        remoteDataSource.reset()

    override suspend fun browse(path: String?): DataResult<BrowseListing, RemoteError> =
        remoteDataSource.browse(path)
}

package eu.anifantakis.commercials.feature.migration_console.domain.data_source

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.feature.migration_console.domain.*

/**
 * Network side of the feature - the only class that touches the HTTP
 * client. Same surface as the repository: the repository is the organizer,
 * this is the wire.
 */
interface RemoteMigrationDataSource {
    suspend fun status(): DataResult<MigrationStatus, RemoteError>
    suspend fun start(request: MigrationStart): DataResult<MigrationStatus, RemoteError>
    suspend fun chooseMapping(mapping: MigrationMapping): DataResult<MigrationStatus, RemoteError>
    suspend fun reset(): DataResult<MigrationStatus, RemoteError>

    /** Lists directories + .sql files ON THE SERVER (null = server home dir). */
    suspend fun browse(path: String?): DataResult<BrowseListing, RemoteError>
}

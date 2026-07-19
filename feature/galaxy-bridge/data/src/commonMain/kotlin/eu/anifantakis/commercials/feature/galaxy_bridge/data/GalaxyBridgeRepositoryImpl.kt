package eu.anifantakis.commercials.feature.galaxy_bridge.data

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyBridgeRepository
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyStart
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyStatus
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyUploadKind
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.data_source.RemoteGalaxyBridgeDataSource

/** Organizer only: all I/O lives in [RemoteGalaxyBridgeDataSource]. */
class GalaxyBridgeRepositoryImpl(
    private val remoteDataSource: RemoteGalaxyBridgeDataSource,
) : GalaxyBridgeRepository {

    override suspend fun status(): DataResult<GalaxyStatus, RemoteError> =
        remoteDataSource.status()

    override suspend fun start(request: GalaxyStart): DataResult<GalaxyStatus, RemoteError> =
        remoteDataSource.start(request)

    override suspend fun reset(): DataResult<GalaxyStatus, RemoteError> =
        remoteDataSource.reset()

    override suspend fun upload(
        kind: GalaxyUploadKind,
        name: String,
        fileName: String,
        bytes: ByteArray,
    ): DataResult<GalaxyStatus, RemoteError> =
        remoteDataSource.upload(kind, name, fileName, bytes)
}

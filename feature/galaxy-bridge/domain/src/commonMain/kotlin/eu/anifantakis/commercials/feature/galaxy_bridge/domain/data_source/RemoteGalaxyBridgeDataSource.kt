package eu.anifantakis.commercials.feature.galaxy_bridge.domain.data_source

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyStart
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyStatus
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyUploadKind

/**
 * Network side of the feature - the only class that touches the HTTP client.
 * Same surface as the repository: the repository is the organizer, this is
 * the wire.
 */
interface RemoteGalaxyBridgeDataSource {
    suspend fun status(): DataResult<GalaxyStatus, RemoteError>
    suspend fun start(request: GalaxyStart): DataResult<GalaxyStatus, RemoteError>
    suspend fun reset(): DataResult<GalaxyStatus, RemoteError>
    suspend fun upload(
        kind: GalaxyUploadKind,
        name: String,
        fileName: String,
        bytes: ByteArray,
    ): DataResult<GalaxyStatus, RemoteError>
}

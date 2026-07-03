package eu.anifantakis.commercials.admin

import eu.anifantakis.commercials.auth.AuthSession
import eu.anifantakis.commercials.auth.authenticatedJsonClient
import eu.anifantakis.commercials.config.AppConfig
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable
data class MigrationStart(
    val dumpPath: String,
    val host: String = "localhost",
    val port: Int = 3306,
    val username: String,
    val password: String,
    val schema: String,
    val createSchema: Boolean = true,
)

@Serializable
data class MigrationFlowChoice(
    val forTv: Int,
    val stationId: String = "",
    val stationName: String = "",
    val addToYaml: Boolean = true,
)

@Serializable
data class MigrationFlowInfo(val forTv: Int, val spots: Long, val placements: Long)

@Serializable
data class MigrationSummary(
    val breaks: Int,
    val customers: Int,
    val customersSynthetic: Int,
    val contracts: Int,
    val contractsSynthetic: Int,
    val contractLines: Int,
    val spots: Int,
    val placements: Int,
    val flowComments: Int,
    val printAudits: Int,
    val dateRange: String,
    val dumpScheduleRows: Long = 0,
    val otherFlowRows: Long = 0,
    val orphanedRows: Long = 0,
    val zeroDateRows: Long = 0,
    val programs: Int = 0,
)

@Serializable
data class MigrationStatus(
    val state: String = "IDLE",
    val log: List<String> = emptyList(),
    val flows: List<MigrationFlowInfo> = emptyList(),
    val summary: MigrationSummary? = null,
    val error: String? = null,
    val schema: String? = null,
)

@Serializable
data class BrowseEntry(val name: String, val isDir: Boolean, val sizeBytes: Long = 0)

@Serializable
data class BrowseListing(val path: String, val parent: String? = null, val entries: List<BrowseEntry> = emptyList())

/**
 * Client for the super-admin migration endpoints. The heavy work (dump
 * replay, transformation) runs ON THE SERVER - the dump path refers to the
 * server's filesystem; this API only steers and polls.
 */
class MigrationApi(session: AuthSession) {

    private val httpClient by lazy { authenticatedJsonClient(session) }

    private val base: String get() = "${AppConfig.require().serverBaseUrl}/api/admin/migration"

    suspend fun status(): Result<MigrationStatus> = runCatching {
        httpClient.get("$base/status").body()
    }

    suspend fun start(request: MigrationStart): Result<MigrationStatus> = runCatching {
        httpClient.post("$base/start") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    suspend fun chooseFlow(choice: MigrationFlowChoice): Result<MigrationStatus> = runCatching {
        httpClient.post("$base/flow") {
            contentType(ContentType.Application.Json)
            setBody(choice)
        }.body()
    }

    suspend fun reset(): Result<MigrationStatus> = runCatching {
        httpClient.post("$base/reset").body()
    }

    /** Lists directories + .sql files ON THE SERVER (null = server home dir). */
    suspend fun browse(path: String?): Result<BrowseListing> = runCatching {
        httpClient.get("$base/browse") {
            if (path != null) url.parameters.append("path", path)
        }.body()
    }
}

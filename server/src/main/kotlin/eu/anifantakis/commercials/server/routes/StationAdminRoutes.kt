package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.migration.removeStationFromYaml
import eu.anifantakis.commercials.migration.stationsYamlFile
import eu.anifantakis.commercials.server.plugins.requireAdmin
import eu.anifantakis.commercials.server.stations.StationRegistry
import eu.anifantakis.commercials.server.stations.databaseTarget
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.DriverManager

@Serializable
data class HostedStationDto(
    val id: String,
    val name: String,
    val database: String,           // host:port/schema (no credentials)
    val reachable: Boolean,
    val placements: Long? = null,
    val dateRange: String? = null,
)

@Serializable
data class DeleteStationRequest(
    /** "safe" = unhost only (yaml entry + grants + live registry); "hard" = also DROP DATABASE. */
    val mode: String,
    /** Must repeat the station id - typed confirmation against fat-finger deletes. */
    val confirmId: String,
)

@Serializable
data class DeleteStationResponse(
    val status: String,
    val grantsRemoved: Int,
    val yamlEntryRemoved: Boolean,
    val databaseDropped: Boolean,
)

/**
 * Hosted-database administration (the Databases screen). Super admin only.
 *
 * Deletion is LIVE: the station vanishes from the registry immediately (every
 * API call for it 404s, and the super admin's implicit grants shrink with the
 * registry), its grants are revoked centrally, and its server.yaml entry is
 * removed so it stays gone after a restart. "hard" additionally drops the
 * station's schema on ITS OWN MySQL server using the credentials that hosted
 * it. Regular users still see the station in their dropdown until their next
 * login, but every request against it is refused.
 */
fun Route.stationAdminRoutes(registry: StationRegistry, authDb: AuthDb) {
    route("/api/admin/stations") {

        get {
            if (!call.requireAdmin()) return@get
            val stations = withContext(Dispatchers.IO) {
                registry.all.map { cfg ->
                    val stats = runCatching { registry.db(cfg.id)?.placementStats() }.getOrNull()
                    HostedStationDto(
                        id = cfg.id,
                        name = cfg.name,
                        database = databaseTarget(cfg.jdbcUrl),
                        reachable = stats != null,
                        placements = stats?.placements,
                        dateRange = stats?.let { s ->
                            if (s.minDate != null) "${s.minDate} .. ${s.maxDate}" else "empty"
                        },
                    )
                }
            }
            call.respond(stations)
        }

        post("/{id}/delete") {
            if (!call.requireAdmin()) return@post
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<DeleteStationRequest>()

            if (request.mode !in setOf("safe", "hard")) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "mode must be 'safe' or 'hard'"))
                return@post
            }
            if (request.confirmId != id) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Confirmation id does not match the station id")
                )
                return@post
            }
            val config = registry.config(id)
            if (config == null) {
                // Not hosted right now, but a stale yaml entry may exist
                // (e.g. added by the CLI without a restart). Clean those up
                // for "safe"; "hard" needs the hosted credentials, so it
                // requires the station to be live.
                if (request.mode == "hard") {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Station '$id' is not hosted right now - hard delete needs its credentials (restart the server first if it is in server.yaml)")
                    )
                    return@post
                }
                val yamlRemoved = withContext(Dispatchers.IO) { removeStationFromYaml(stationsYamlFile(), id) }
                val grantsRemoved = withContext(Dispatchers.IO) { authDb.deleteGrantsForStation(id) }
                if (!yamlRemoved && grantsRemoved == 0) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown station '$id'"))
                } else {
                    call.respond(
                        DeleteStationResponse(
                            status = "Station '$id' was not hosted; removed leftover configuration",
                            grantsRemoved = grantsRemoved,
                            yamlEntryRemoved = yamlRemoved,
                            databaseDropped = false,
                        )
                    )
                }
                return@post
            }

            val response = withContext(Dispatchers.IO) {
                val grantsRemoved = authDb.deleteGrantsForStation(id)
                val yamlRemoved = removeStationFromYaml(stationsYamlFile(), id)
                registry.remove(id)

                var dropped = false
                if (request.mode == "hard") {
                    // host:port/schema from the station's own jdbcUrl - the drop
                    // happens on the server that actually hosts the schema.
                    val target = databaseTarget(config.jdbcUrl)
                    val hostPort = target.substringBeforeLast('/')
                    val schema = target.substringAfterLast('/')
                    require(Regex("[a-zA-Z0-9_]{1,64}").matches(schema)) { "Refusing to drop suspicious schema name '$schema'" }
                    DriverManager.getConnection(
                        "jdbc:mysql://$hostPort/?useSSL=false&allowPublicKeyRetrieval=true",
                        config.username, config.password
                    ).use { c ->
                        c.createStatement().use { it.executeUpdate("DROP DATABASE IF EXISTS `$schema`") }
                    }
                    dropped = true
                }

                DeleteStationResponse(
                    status = "Station '$id' removed" + if (dropped) " and its database dropped" else "",
                    grantsRemoved = grantsRemoved,
                    yamlEntryRemoved = yamlRemoved,
                    databaseDropped = dropped,
                )
            }
            call.respond(response)
        }
    }
}

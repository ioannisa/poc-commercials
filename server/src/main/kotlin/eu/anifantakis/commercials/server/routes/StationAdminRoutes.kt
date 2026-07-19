package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.migration.removeGroupFromYaml
import eu.anifantakis.commercials.migration.removeStationFromYaml
import eu.anifantakis.commercials.migration.stationsYamlFile
import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.plugins.requireAdmin
import eu.anifantakis.commercials.server.stations.StationRegistry
import eu.anifantakis.commercials.server.stations.databaseTarget
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.DriverManager

@Serializable
data class HostedStationDto(
    val id: String,
    val name: String,
    val database: String,           // host:port/schema (no credentials)
    val reachable: Boolean,
    val placements: Long? = null,
    val dateRange: String? = null,
    /** The group whose database this station shares (with its siblings). */
    val groupId: String = "",
    val groupName: String = "",
    /** The other stations in the same database - what a drop-group would also destroy. */
    val siblings: List<String> = emptyList(),
)

@Serializable
data class DeleteStationRequest(
    /**
     * - "safe"       unhost only (yaml entry + grants + live registry); data untouched.
     * - "purge"      also DELETE this station's rows from the group database.
     *                Its siblings, and the group's shared customers/contracts, survive.
     * - "drop-group" DROP the whole group DATABASE - every station in it dies.
     */
    val mode: String,
    /**
     * Typed confirmation. The STATION id for "safe"/"purge"; the GROUP id for
     * "drop-group" - deliberately a different string, so the destructive one
     * cannot be reached by muscle memory.
     */
    val confirmId: String,
)

@Serializable
data class DeleteStationResponse(
    val status: String,
    val grantsRemoved: Int,
    val yamlEntryRemoved: Boolean,
    val databaseDropped: Boolean,
    /** Rows removed from the group database by a "purge". */
    val rowsPurged: Long = 0,
    /** Stations that went with a "drop-group". */
    val stationsRemoved: List<String> = emptyList(),
)

/**
 * Hosted-database administration (the Databases screen). Super admin only.
 *
 * Deletion is LIVE: the station vanishes from the registry immediately (every
 * API call for it 404s, and the super admin's implicit grants shrink with the
 * registry), its grants are revoked centrally, and its server.yaml entry is
 * removed so it stays gone after a restart.
 *
 * ⚠ A STATION NO LONGER OWNS A DATABASE - its GROUP does, and its siblings live
 * in the same one. So the old "hard" mode (DROP DATABASE for a station) would
 * have taken the sibling stations with it. It is rejected, and replaced by two
 * honest modes: `purge` deletes just this station's rows, and `drop-group`
 * drops the whole group database and says which stations it is about to destroy.
 */
@OptIn(ExperimentalKtorApi::class)
fun Route.stationAdminRoutes(registry: StationRegistry, authDb: AuthDb) {
    route("/api/admin/stations") {

        /**
         * List all hosted stations with placement stats, group, and sibling stations.
         *
         * Tag: Admin
         */
        get {
            if (!call.requireAdmin()) return@get
            val stations = withContext(Dispatchers.IO) {
                registry.all.map { cfg ->
                    val stats = runCatching { registry.db(cfg.id)?.placementStats() }.getOrNull()
                    val group = registry.group(cfg.id)
                    HostedStationDto(
                        id = cfg.id,
                        name = cfg.name,
                        database = group?.let { databaseTarget(it.jdbcUrl) } ?: "",
                        reachable = stats != null,
                        placements = stats?.placements,
                        dateRange = stats?.let { s ->
                            if (s.minDate != null) "${s.minDate} .. ${s.maxDate}" else "empty"
                        },
                        groupId = group?.id ?: "",
                        groupName = group?.name ?: group?.id ?: "",
                        siblings = group?.stations.orEmpty().map { it.id }.filter { it != cfg.id },
                    )
                }
            }
            call.respond(stations)
        }

        /**
         * Delete a station by id: safe-unhost, purge its rows, or drop the whole group database.
         *
         * Tag: Admin
         */
        post("/{id}/delete") {
            if (!call.requireAdmin()) return@post
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<DeleteStationRequest>()

            if (request.mode == "hard") {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "error" to "'hard' no longer exists: a station does not own a database - its GROUP does, " +
                            "and dropping it would destroy the sibling stations too. Use 'purge' to delete only " +
                            "this station's rows, or 'drop-group' to drop the whole group database."
                    )
                )
                return@post
            }
            if (request.mode !in setOf("safe", "purge", "drop-group")) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "mode must be 'safe', 'purge' or 'drop-group'")
                )
                return@post
            }

            val config = registry.config(id)
            val group = registry.group(id)
            if (config == null || group == null) {
                // Not hosted right now, but a stale yaml entry may exist (e.g.
                // added by the CLI without a restart). Clean those up for
                // "safe"; the destructive modes need the hosted credentials.
                if (request.mode != "safe") {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Station '$id' is not hosted right now - '${request.mode}' needs its group's credentials (restart the server first if it is in server.yaml)")
                    )
                    return@post
                }
                if (request.confirmId != id) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Confirmation id does not match the station id"))
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

            // The confirmation string differs per mode: dropping the group is a
            // different decision from deleting a station, so it takes a
            // different word.
            val expected = if (request.mode == "drop-group") group.id else id
            if (request.confirmId != expected) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "error" to if (request.mode == "drop-group")
                            "Confirmation must be the GROUP id '${group.id}' - dropping it destroys every station in it (${group.stations.joinToString { it.id }})"
                        else "Confirmation id does not match the station id"
                    )
                )
                return@post
            }

            val response = withContext(Dispatchers.IO) {
                when (request.mode) {
                    "drop-group" -> {
                        val stationIds = group.stations.map { it.id }
                        val grantsRemoved = stationIds.sumOf { authDb.deleteGrantsForStation(it) }
                        val yamlRemoved = removeGroupFromYaml(stationsYamlFile(), group.id)
                        registry.removeGroup(group.id)

                        val target = databaseTarget(group.jdbcUrl)
                        val hostPort = target.substringBeforeLast('/')
                        val schema = target.substringAfterLast('/')
                        require(Regex("[a-zA-Z0-9_]{1,64}").matches(schema)) {
                            "Refusing to drop suspicious schema name '$schema'"
                        }
                        DriverManager.getConnection(
                            "jdbc:mysql://$hostPort/?useSSL=false&allowPublicKeyRetrieval=true",
                            group.username, group.password
                        ).use { c ->
                            c.createStatement().use { it.executeUpdate("DROP DATABASE IF EXISTS `$schema`") }
                        }
                        DeleteStationResponse(
                            status = "Group '${group.id}' dropped with its station(s) ${stationIds.joinToString()}",
                            grantsRemoved = grantsRemoved,
                            yamlEntryRemoved = yamlRemoved,
                            databaseDropped = true,
                            stationsRemoved = stationIds,
                        )
                    }

                    else -> {
                        // purge BEFORE unhosting: the rows are reached through the
                        // station's own view, which disappears with it.
                        val purged = if (request.mode == "purge") {
                            registry.db(id)?.connection()?.use { purgeStationRows(it, id) } ?: 0L
                        } else 0L

                        val grantsRemoved = authDb.deleteGrantsForStation(id)
                        val yamlRemoved = removeStationFromYaml(stationsYamlFile(), id)
                        registry.removeStation(id)

                        DeleteStationResponse(
                            status = "Station '$id' removed" +
                                if (purged > 0) " and its $purged rows deleted from ${group.id}'s database " +
                                    "(the group's customers and contracts, and its other stations, are untouched)" else "",
                            grantsRemoved = grantsRemoved,
                            yamlEntryRemoved = yamlRemoved,
                            databaseDropped = false,
                            rowsPurged = purged,
                        )
                    }
                }
            }
            call.respond(response)
        }.describe {
            summary = "Delete a station: safe-unhost, purge just its rows, or drop the whole group database (mode + confirm id required)."
            tag("Admin")
        }
    }
}

/**
 * Deletes everything belonging to ONE station from its group's database, in
 * foreign-key order. The GROUP-scoped tables (customers, contracts,
 * contract_lines, spot_types, the verbatim legacy copies) are deliberately left
 * alone: they belong to the company, not to this station, and its siblings are
 * still using them.
 */
private fun purgeStationRows(c: Connection, stationId: String): Long {
    var total = 0L
    fun exec(sql: String) {
        c.prepareStatement(sql).use { ps ->
            ps.setString(1, stationId)
            total += ps.executeUpdate()
        }
    }
    // Airings first - they reference spots, programmes AND breaks; then the
    // breaks, which reference programmes; then the rest.
    exec("DELETE FROM placements WHERE station_id = ?")
    exec("DELETE FROM breaks WHERE station_id = ?")
    exec("DELETE FROM spots WHERE station_id = ?")
    exec("DELETE FROM programs WHERE station_id = ?")
    exec("DELETE FROM flow_comments WHERE station_id = ?")
    exec("DELETE FROM print_audit WHERE station_id = ?")
    exec("DELETE FROM email_log WHERE station_id = ?")
    exec("DELETE FROM station_meta WHERE station_id = ?")
    exec("DELETE FROM stations WHERE id = ?")
    return total
}

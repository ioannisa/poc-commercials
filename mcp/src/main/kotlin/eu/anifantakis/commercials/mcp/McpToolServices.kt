package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.server.auth.StationGrant
import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.scheduler.StationDb
import eu.anifantakis.commercials.server.stations.StationRegistry

/**
 * A tool-level failure carrying a client-facing message. Thrown by tool bodies
 * to short-circuit with a clear reason; the [runTool] wrapper turns it into an
 * MCP tool error (`isError = true`) rather than crashing the session.
 */
class McpToolException(message: String) : Exception(message)

/** A station the caller may touch: its DB pool + the caller's grant (its role drives scoping). */
data class StationAccess(val db: StationDb, val grant: StationGrant)

/** One station visible to the caller (`list_stations` output). */
data class StationInfo(val id: String, val name: String, val role: String, val clientCode: String?)

/**
 * The backend surface the MCP tools call. Centralises station resolution and
 * authorization, mirroring the server's `Security.stationAccessOrRespond` and
 * role checks so the HTTP and stdio transports enforce the SAME rules.
 *
 * DB access is blocking JDBC - callers run these inside [runTool], which hops to
 * `Dispatchers.IO`.
 */
class McpToolServices(
    private val registry: StationRegistry,
) {
    /**
     * Resolve a station the caller is entitled to, else throw a clear tool error
     * (mirrors the route helper's 400 missing / 403 no-grant / 404 unknown).
     */
    fun resolveStation(caller: McpCaller, stationId: String?): StationAccess {
        if (stationId.isNullOrBlank()) throw McpToolException("Parameter 'station' is required")
        val grant = caller.grantFor(stationId)
            ?: throw McpToolException("No access to station '$stationId'")
        // Pool creation + schema bootstrap are lazy and blocking on first touch.
        val db = registry.db(stationId)
            ?: throw McpToolException("Unknown station '$stationId'")
        return StationAccess(db, grant)
    }

    /** The stations this caller may see, each with the caller's role/clientCode on it. */
    fun stations(caller: McpCaller): List<StationInfo> =
        registry.ids.filter { caller.grantFor(it) != null }.map { id ->
            val grant = caller.grantFor(id)!!
            StationInfo(
                id = id,
                name = registry.config(id)?.name ?: id,
                role = grant.role.name,
                clientCode = grant.clientCode,
            )
        }

    /** True when the caller only sees their own client's data on this station. */
    fun isCustomerScoped(grant: StationGrant): Boolean = grant.role == UserRole.CUSTOMER_VIEWER

    /** For a customer-scoped caller, forbid querying another party's code. */
    fun requireCode(grant: StationGrant, code: String) {
        if (isCustomerScoped(grant) && grant.clientCode != code) {
            throw McpToolException(
                "Customer-scoped access: you may only query your own client code (${grant.clientCode})."
            )
        }
    }
}

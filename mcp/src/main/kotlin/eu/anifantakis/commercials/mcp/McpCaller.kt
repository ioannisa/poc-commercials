package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.server.auth.AuthUser
import eu.anifantakis.commercials.server.auth.StationGrant
import eu.anifantakis.commercials.server.auth.UserRole

/**
 * The identity a tool call runs as - the same [AuthUser] the HTTP bearer
 * principal carries. Wrapping it here keeps the tools transport-agnostic: the
 * per-station grant/role checks are identical to the REST API's, because both
 * resolve the caller from the same bearer token via the Ktor `server`'s `/mcp`.
 */
data class McpCaller(val user: AuthUser) {
    val isAdmin: Boolean get() = user.isAdmin
    fun grantFor(stationId: String): StationGrant? = user.grantFor(stationId)
    fun hasRoleAnywhere(role: UserRole): Boolean = user.hasRoleAnywhere(role)

    companion object {
        fun of(user: AuthUser): McpCaller = McpCaller(user)
    }
}

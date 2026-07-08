package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.server.auth.AuthUser
import eu.anifantakis.commercials.server.auth.StationGrant
import eu.anifantakis.commercials.server.auth.UserRole

/**
 * The identity a tool call runs as - the same [AuthUser] the HTTP bearer
 * principal carries. Wrapping it here lets the tools stay transport-agnostic:
 * per-station grant/role checks are identical whether the tools are reached over
 * HTTP (the Ktor `server`) or stdio (`:mcp-stdio`).
 */
data class McpCaller(val user: AuthUser) {
    val isAdmin: Boolean get() = user.isAdmin
    fun grantFor(stationId: String): StationGrant? = user.grantFor(stationId)
    fun hasRoleAnywhere(role: UserRole): Boolean = user.hasRoleAnywhere(role)

    companion object {
        fun of(user: AuthUser): McpCaller = McpCaller(user)
    }
}

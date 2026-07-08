package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.server.auth.AuthUser
import eu.anifantakis.commercials.server.auth.StationGrant
import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.stations.DbConnectionConfig
import eu.anifantakis.commercials.server.stations.HostingConfig
import eu.anifantakis.commercials.server.stations.StationConfig
import eu.anifantakis.commercials.server.stations.StationRegistry

/**
 * Test fixtures. The [StationRegistry] is built from an in-memory HostingConfig;
 * no station pool is ever opened because the tests only exercise paths that stop
 * before `registry.db(id)` connects (missing/no-grant/unknown, or pure listing).
 */
internal fun grant(
    stationId: String,
    role: UserRole = UserRole.NORMAL_USER,
    clientCode: String? = null,
) = StationGrant(stationId, role, clientCode)

internal fun caller(vararg grants: StationGrant, admin: Boolean = false) =
    McpCaller(AuthUser(id = 1, username = "user", displayName = "User", isAdmin = admin, grants = grants.toList()))

internal fun station(id: String, name: String = id) =
    StationConfig(id = id, name = name, jdbcUrl = "jdbc:mysql://localhost/$id", username = "u", password = "p")

internal fun registryOf(vararg stations: StationConfig) =
    StationRegistry(
        HostingConfig(
            central = DbConnectionConfig("jdbc:mysql://localhost/central", "u", "p"),
            stations = stations.toList(),
        )
    )

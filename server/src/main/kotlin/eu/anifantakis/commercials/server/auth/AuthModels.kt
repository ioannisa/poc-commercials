package eu.anifantakis.commercials.server.auth

/**
 * The three access layers, granted PER STATION:
 * - [NORMAL_USER]: full access - everything the app does today.
 * - [REPORT_VIEWER]: sees all of the station's data and reports, read-only.
 * - [CUSTOMER_VIEWER]: read-only AND scoped - sees only the spots belonging
 *   to their own [StationGrant.clientCode] on that station.
 */
enum class UserRole { NORMAL_USER, REPORT_VIEWER, CUSTOMER_VIEWER }

/**
 * One user's privilege on one hosted station. A user can be NORMAL_USER on
 * "Crete TV" and REPORT_VIEWER on "Radio 984" at the same time.
 *
 * @param clientCode set only for [UserRole.CUSTOMER_VIEWER] - the client code
 *        (Κωδ. Πελ.) whose commercials this customer may see on that station.
 */
data class StationGrant(
    val stationId: String,
    val role: UserRole,
    val clientCode: String?
)

/**
 * The authenticated user, attached to the call as the auth principal.
 * Station access is decided per request from [grants].
 *
 * @param isAdmin the config-managed super administrator (from stations.yaml):
 *        may manage users, and receives synthesized NORMAL_USER grants on
 *        every hosted station. Its password/recovery are managed in the YAML,
 *        never via the API.
 */
data class AuthUser(
    val id: Long,
    val username: String,
    val displayName: String,
    val isAdmin: Boolean,
    val grants: List<StationGrant>
) {
    fun grantFor(stationId: String): StationGrant? = grants.firstOrNull { it.stationId == stationId }
    fun hasRoleAnywhere(role: UserRole): Boolean = grants.any { it.role == role }
}

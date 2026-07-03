package eu.anifantakis.commercials.server.auth

/**
 * The three access layers:
 * - [NORMAL_USER]: full access - everything the app does today.
 * - [REPORT_VIEWER]: sees all data and reports, but read-only (no editing).
 * - [CUSTOMER_VIEWER]: read-only AND scoped - sees only the spots belonging
 *   to their own [AuthUser.clientCode]; the server filters their data.
 */
enum class UserRole { NORMAL_USER, REPORT_VIEWER, CUSTOMER_VIEWER }

/**
 * The authenticated user, attached to the call as the auth principal.
 *
 * @param clientCode set only for [UserRole.CUSTOMER_VIEWER] - the client code
 *        (Κωδ. Πελ.) whose commercials this customer is allowed to see.
 */
data class AuthUser(
    val id: Long,
    val username: String,
    val displayName: String,
    val role: UserRole,
    val clientCode: String?
)

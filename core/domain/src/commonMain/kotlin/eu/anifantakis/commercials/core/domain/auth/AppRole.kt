package eu.anifantakis.commercials.core.domain.auth

/**
 * Client-side mirror of the server's user roles.
 *
 * - [NORMAL_USER]: full access - everything the app does today.
 * - [REPORT_VIEWER]: sees all data and reports, but read-only.
 * - [CUSTOMER_VIEWER]: read-only AND server-scoped to their own client's
 *   spots (the server filters /api/schedule for them, so everything this
 *   client renders or prints is already just their data).
 */
enum class AppRole {
    NORMAL_USER, REPORT_VIEWER, CUSTOMER_VIEWER;

    val canEdit: Boolean get() = this == NORMAL_USER

    val label: String
        get() = when (this) {
            NORMAL_USER -> "Normal User"
            REPORT_VIEWER -> "Report Viewer"
            CUSTOMER_VIEWER -> "Customer Viewer"
        }

    companion object {
        /** Unknown role names fall back to the least-privileged sensible tier. */
        fun parse(name: String): AppRole =
            entries.firstOrNull { it.name == name } ?: REPORT_VIEWER
    }
}

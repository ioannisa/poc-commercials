package eu.anifantakis.commercials.core.presentation.util

import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey

/**
 * Presentation label for a domain [AppRole]: the domain enum stays pure
 * (no StringKey — that's a presentation type), the UI resolves via
 * `Strings[role.toStringKey()]`.
 */
fun AppRole.toStringKey(): StringKey = when (this) {
    AppRole.NORMAL_USER -> StringKey.ROLE_NORMAL_USER
    AppRole.REPORT_VIEWER -> StringKey.ROLE_REPORT_VIEWER
    AppRole.CUSTOMER_VIEWER -> StringKey.ROLE_CUSTOMER_VIEWER
}

package eu.anifantakis.commercials.core.domain.auth

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * One station this user may access, as granted by the server: the station's
 * id/display name plus THIS user's role on it (and, for customer viewers, the
 * client code their data is scoped to).
 *
 * Domain vocabulary: it appears in the session contract [UserSession] that the
 * presentation layer depends on, and the data layer persists it inside its
 * `StoredSession` (hence `@Serializable` - a pure-Kotlin, non-platform trait).
 *
 * `@Immutable`: a read-only value class the chrome renders directly, so the
 * annotation lets Compose (and the IDE) treat it as stable / skippable.
 */
@Immutable
@Serializable
data class StationAccess(
    val id: String,
    val name: String,
    val role: String,
    val clientCode: String? = null,
)

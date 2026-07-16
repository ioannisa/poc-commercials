package eu.anifantakis.commercials.feature.auth.domain.model

/** One station the server granted, as returned by login. */
data class GrantedStation(
    val id: String,
    val name: String,
    val role: String,
    val clientCode: String? = null,
)

/** A successful login, before any session policy is applied. */
data class LoginResult(
    val token: String,
    val displayName: String,
    val isAdmin: Boolean,
    /** Server-wide: whether the server serves the Swagger UI (server.yaml `swagger`). */
    val swaggerEnabled: Boolean = false,
    val stations: List<GrantedStation>,
    /** After an admin reset / on a fresh account: the app traps the user on a
     *  change-password screen until they pick a new one. */
    val mustChangePassword: Boolean = false,
)

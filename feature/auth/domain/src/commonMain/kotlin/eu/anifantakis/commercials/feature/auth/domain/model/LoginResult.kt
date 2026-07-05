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
    val stations: List<GrantedStation>,
)

package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.config.ServerBuildInfo
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * What GET /version answers: this server's own build stamp plus the desktop
 * auto-update advertisement (app_settings rows published by the super admin
 * via PUT /api/admin/app-update). Fields absent until a release is published -
 * the desktop client no-ops on an empty advertisement.
 */
@Serializable
data class VersionInfoDto(
    val serverVersion: String,
    /** Latest published desktop version, e.g. "1.2.0". */
    val latest: String? = null,
    /** Oldest still-supported desktop version: older clients MUST update. */
    val minSupported: String? = null,
    /** "dmg" / "msi" / "deb" → installer URL. Relative URLs (e.g.
     *  "/downloads/CM-1.2.0.msi") are resolved by the client against its own
     *  server.baseUrl, so the same rows serve every hostname. */
    val installers: Map<String, String> = emptyMap(),
)

/**
 * The OPEN version endpoint (next to /health, mounted OUTSIDE authenticate):
 * a desktop client below minSupported must be able to learn that - and fetch
 * the installer - BEFORE anyone can log in, so this cannot sit behind a
 * bearer token. It leaks only version numbers and installer URLs, the same
 * information the login screen of any auto-updating app exposes.
 */
fun Route.versionRoute(authDb: AuthDb) {
    /**
     * Return this server's build version and the published desktop-app update advertisement.
     *
     * Tag: Updates
     */
    get("/version") {
        val ad = withContext(Dispatchers.IO) { authDb.appUpdateSettings() }
        call.respond(
            VersionInfoDto(
                serverVersion = ServerBuildInfo.version,
                latest = ad.latest,
                minSupported = ad.minSupported,
                installers = ad.installers,
            )
        )
    }
}

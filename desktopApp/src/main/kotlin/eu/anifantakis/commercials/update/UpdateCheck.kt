package eu.anifantakis.commercials.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The open GET /version response (mirror of the server's VersionInfoDto -
 * DTOs are duplicated per side by house convention, same as the REST API).
 */
@Serializable
data class VersionInfo(
    val serverVersion: String = "",
    val latest: String? = null,
    val minSupported: String? = null,
    /** "dmg" / "msi" / "deb" → installer URL (absolute, or server-relative). */
    val installers: Map<String, String> = emptyMap(),
)

/** What the startup check concluded. */
sealed interface UpdateDecision {
    /** Up to date, no advertisement, or no installer for this OS - nothing to show. */
    data object None : UpdateDecision

    /**
     * A newer version is published and its installer is reachable.
     * [mandatory]: this build is below minSupported - the dialog blocks the
     * app (update or exit) instead of offering "later".
     */
    data class Available(
        val latest: String,
        val mandatory: Boolean,
        val installerUrl: String,
    ) : UpdateDecision
}

/**
 * The startup auto-update check. Deliberately BEST-EFFORT: any failure
 * (server down, old server without /version, junk response) resolves to
 * "no update" - the check must never break or delay app startup, which is
 * why it also runs OFF the startup path (see main.kt's LaunchedEffect).
 *
 * The update itself is NOT silent: this only detects and offers; the user
 * always drives the download + install (see UpdateDialog/UpdateDownloader).
 */
object UpdateCheck {

    private val json = Json { ignoreUnknownKeys = true }

    /** Never throws - null on any failure (unreachable, non-200, junk body). */
    fun fetch(baseUrl: String): VersionInfo? = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/version"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return@runCatching null
        json.decodeFromString<VersionInfo>(response.body())
    }.getOrNull()

    fun decide(current: String, info: VersionInfo, baseUrl: String): UpdateDecision {
        val latest = info.latest ?: return UpdateDecision.None
        if (compareVersions(current, latest) >= 0) return UpdateDecision.None
        // Newer version but no installer for this OS: nothing actionable to
        // offer - stay silent rather than show a dead-end dialog.
        val url = installerUrlForCurrentOs(info.installers, baseUrl) ?: return UpdateDecision.None
        val mandatory = info.minSupported != null && compareVersions(current, info.minSupported) < 0
        return UpdateDecision.Available(latest = latest, mandatory = mandatory, installerUrl = url)
    }

    /**
     * The installer for THIS machine, resolved to an absolute URL. Relative
     * values ("/downloads/x.msi") resolve against [baseUrl] - the server
     * advertises the same rows to every hostname it is reached through.
     *
     * Spaces are %-encoded: jpackage names its artifacts after packageName
     * ("Commercials Manager 2-1.1.0.dmg"), and URI.create throws on a raw
     * space - an admin pasting the natural filename must not break updates.
     */
    fun installerUrlForCurrentOs(
        installers: Map<String, String>,
        baseUrl: String,
        os: HostOs = HostOs.detect(),
    ): String? {
        val raw = installers[os.installerKey] ?: return null
        val resolved = if (raw.startsWith("http://") || raw.startsWith("https://")) raw
        else baseUrl.trimEnd('/') + (if (raw.startsWith("/")) raw else "/$raw")
        return resolved.replace(" ", "%20")
    }
}

package eu.anifantakis.commercials.reports

/**
 * A path on THIS machine to the current station's report logo.
 *
 * `server.yaml` holds the logo as a PATH, and a path only means something to the
 * machine it belongs to. That is fine for every platform that renders reports on
 * the SERVER - the server reads its own file and never trusts a caller's path.
 * The DESKTOP is the exception: it renders in-process, and it is usually not the
 * server's machine, so a server path would resolve to nothing.
 *
 * So the desktop asks for the IMAGE, not the path (`GET /api/reports/logo`),
 * keeps it in a local cache file, and hands Jasper that. Everyone else gets
 * [NoStationLogoCache] and null - not because they have no logo, but because
 * theirs is applied server-side where it belongs.
 *
 * Returns null when the station has no logo. The template then prints its "LOGO"
 * placeholder: a missing logo costs a logo, never a report.
 */
interface StationLogoCache {
    suspend fun localLogoPath(): String?
}

/**
 * For the platforms that render server-side (web, Android, iOS). The server puts
 * the logo on the report itself, so there is nothing for the client to fetch.
 */
class NoStationLogoCache : StationLogoCache {
    override suspend fun localLogoPath(): String? = null
}

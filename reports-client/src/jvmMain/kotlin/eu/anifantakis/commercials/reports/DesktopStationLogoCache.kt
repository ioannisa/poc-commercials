package eu.anifantakis.commercials.reports

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.core.domain.auth.UserSession
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The desktop renders reports IN-PROCESS, so Jasper needs a file it can open on
 * THIS machine - and the desktop is usually not the server's machine. It
 * therefore fetches the logo's BYTES from the server and keeps them in a cache
 * file of its own; `server.yaml`'s path stays where it belongs.
 *
 * Offline-tolerant on purpose: in-process rendering is the one report path that
 * works with no server, so a failed refresh falls back to whatever was cached
 * last, and only an explicit 404 (the station really has no logo any more)
 * clears it. Losing the network must not cost you a logo you already have.
 *
 * Fetched once per station per run - a logo is not hot data.
 */
class DesktopStationLogoCache(
    private val api: ApiHttpClient,
    private val session: UserSession,
) : StationLogoCache {

    private val dir = File(System.getProperty("java.io.tmpdir"), "commercials-manager/logos")
    private val mutex = Mutex()
    private val resolved = mutableMapOf<String, String?>()

    override suspend fun localLogoPath(): String? {
        val stationId = session.selectedStation?.id ?: return null
        return mutex.withLock {
            if (resolved.containsKey(stationId)) return@withLock resolved[stationId]
            val path = withContext(Dispatchers.IO) { fetchOrCached(stationId) }
            resolved[stationId] = path
            path
        }
    }

    private suspend fun fetchOrCached(stationId: String): String? {
        val file = File(dir, stationId)
        try {
            // ApiHttpClient stamps ?station= on every call, so this is the
            // selected station's logo - and its grant was already checked.
            val bytes = api.client.get("/api/reports/logo").readRawBytes()
            dir.mkdirs()
            file.writeBytes(bytes)
        } catch (e: ClientRequestException) {
            // 404 = this station HAS no logo. Anything stale must go.
            if (e.response.status == HttpStatusCode.NotFound) {
                file.delete()
                return null
            }
        } catch (_: Exception) {
            // Offline / server down. Keep printing with the last logo we have.
        }
        return file.takeIf { it.isFile && it.length() > 0 }?.absolutePath
    }
}

package eu.anifantakis.commercials.core.data.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Serializable
private data class Cell(val time: String)

/**
 * The client MUST ask for compression, or the server's is dead weight.
 *
 * This is not a theoretical concern. The month grid ships **7.79 MB** of JSON for
 * a busy month (13,009 commercials) and **329 KB** gzipped - a 23.7x cut. But a
 * browser adds `Accept-Encoding` on its own and the JVM's CIO engine does NOT, so
 * on DESKTOP - the primary product - the whole saving hangs on the ContentEncoding
 * plugin being installed in [CommonHttpClient].
 *
 * Compiling proves nothing here: drop the plugin and everything still builds, and
 * still works, just three-and-a-half megabytes heavier. Hence a test that reads
 * the header actually put on the wire.
 */
class ContentEncodingTest {

    @Test
    fun everyRequestAsksForCompression() = runTest {
        var acceptEncoding: String? = null
        val api = ApiHttpClient(
            tokenProvider = { "tok" },
            onUnauthorized = {},
            stationProvider = { "crete-tv" },
            logging = false,
            baseUrlProvider = { "http://server" },
            engine = MockEngine { request ->
                acceptEncoding = request.headers[HttpHeaders.AcceptEncoding]
                respond(
                    content = """[{"time":"12:20"}]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        api.get<List<Cell>>("/api/schedule")

        val header = acceptEncoding
        assertNotNull(header, "the client sent no Accept-Encoding at all - the server's compression is then dead weight")
        assertTrue(header.contains("gzip"), "expected gzip in Accept-Encoding, got '$header'")
    }
}

package eu.anifantakis.commercials.core.data.network

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class Item(val id: Int)

/**
 * MockEngine tests for [ApiHttpClient] - the seam every Remote*DataSource
 * test uses: construct the client with a MockEngine and lambda providers,
 * no server and no Koin required.
 */
class AuthHttpClientTest {

    private fun api(
        token: String? = "tok-123",
        station: String? = "my-sample",
        onUnauthorized: () -> Unit = {},
        engine: MockEngine,
    ) = ApiHttpClient(
        tokenProvider = { token },
        onUnauthorized = onUnauthorized,
        stationProvider = { station },
        engine = engine,
        logging = false,
        baseUrlProvider = { "http://server" },
    )

    /**
     * The regression this guards: on a 401 the browser used to crash with
     * NoTransformationFoundException because `body()` tried to deserialize the
     * (non-JSON) error page as the expected type. Now a 401 must clear the
     * session and surface as a typed UNAUTHORIZED failure - never reach the
     * deserializer.
     */
    @Test
    fun unauthorizedClearsSessionAndMapsToUnauthorized() = runTest {
        var cleared = false
        val api = api(
            token = "stale-token",
            onUnauthorized = { cleared = true },
            engine = MockEngine { respond("Unauthorized", HttpStatusCode.Unauthorized) },
        )

        val result = api.get<List<Item>>("/api/breaks")

        assertEquals(DataResult.Failure(DataError.Network.UNAUTHORIZED), result)
        assertTrue(cleared, "a 401 must clear the session so the app returns to Login")
    }

    /** Bearer token, station parameter and base URL are attached per request. */
    @Test
    fun attachesBearerStationAndBaseUrl() = runTest {
        var seenAuth: String? = null
        var seenUrl: String? = null
        val api = api(
            engine = MockEngine { request ->
                seenAuth = request.headers[HttpHeaders.Authorization]
                seenUrl = request.url.toString()
                respond(
                    content = """[{"id":7}]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val result = api.get<List<Item>>("/api/breaks", "year" to 2026)

        assertEquals(DataResult.Success(listOf(Item(7))), result)
        assertEquals("Bearer tok-123", seenAuth)
        assertEquals("http://server/api/breaks?year=2026&station=my-sample", seenUrl)
    }

    /** Non-401 failures map to their typed error without touching the session. */
    @Test
    fun otherErrorsMapWithoutClearingSession() = runTest {
        var cleared = false
        val api = api(
            onUnauthorized = { cleared = true },
            engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) },
        )

        val result = api.get<List<Item>>("/api/breaks")

        assertEquals(DataResult.Failure(DataError.Network.INTERNAL_SERVER_ERROR), result)
        assertTrue(!cleared, "a 500 is not an auth failure - the session must stay")
    }
}

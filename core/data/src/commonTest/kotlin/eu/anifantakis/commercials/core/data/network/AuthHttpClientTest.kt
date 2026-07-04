package eu.anifantakis.commercials.core.data.network

import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private data class Item(val id: Int)

class AuthHttpClientTest {

    /**
     * The regression this guards: on a 401 the browser used to crash with
     * NoTransformationFoundException because `body()` tried to deserialize the
     * (non-JSON) error page as the expected type. Now a 401 must surface as a
     * typed [SessionExpiredException] AND clear the session - never reach the
     * deserializer.
     */
    @Test
    fun unauthorizedClearsSessionAndThrowsSessionExpired() = runTest {
        var cleared = false
        val client = authenticatedJsonClient(
            tokenProvider = { "stale-token" },
            onUnauthorized = { cleared = true },
            engine = MockEngine { respond("Unauthorized", HttpStatusCode.Unauthorized) },
        )

        assertFailsWith<SessionExpiredException> {
            client.get("http://server/api/breaks").body<List<Item>>()
        }
        assertTrue(cleared, "a 401 must clear the session so the app returns to Login")
    }

    /** The token is attached per request. */
    @Test
    fun attachesBearerToken() = runTest {
        var seenAuth: String? = null
        val client = authenticatedJsonClient(
            tokenProvider = { "tok-123" },
            onUnauthorized = {},
            engine = MockEngine { request ->
                seenAuth = request.headers[HttpHeaders.Authorization]
                respond(
                    content = """[{"id":7}]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val items: List<Item> = client.get("http://server/api/breaks").body()
        assertEquals(listOf(Item(7)), items)
        assertEquals("Bearer tok-123", seenAuth)
    }

    /** Non-401 failures propagate as-is (not swallowed, not mistaken for auth). */
    @Test
    fun otherErrorsPropagateWithoutClearingSession() = runTest {
        var cleared = false
        val client = authenticatedJsonClient(
            tokenProvider = { "tok" },
            onUnauthorized = { cleared = true },
            engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) },
        )

        assertFailsWith<Throwable> { client.get("http://server/api/breaks").body<List<Item>>() }
        assertTrue(!cleared, "a 500 is not an auth failure - the session must stay")
    }
}

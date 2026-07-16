package eu.anifantakis.commercials.core.data.session

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.core.domain.auth.StationAccess
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * [SessionKeepAlive] is what makes "an open app is never logged out" true, and the
 * thing it must never do is log someone out ITSELF. A 401 is the only end of a
 * session; every other failure - a server restarting, a train tunnel, a laptop lid -
 * has to leave the user exactly where they were.
 *
 * That asymmetry is the whole design, and it is exactly where a plausible-looking
 * `if (keep-alive failed) clear()` would quietly destroy a perfectly good session.
 *
 * These drive `knock()` directly rather than `run()`: the loop around it is three
 * lines (knock, delay, repeat), while MockEngine answers on the Ktor engine's OWN
 * dispatcher, which runTest's virtual clock does not govern - so a test built on
 * `advanceTimeBy` would be asserting against requests that had not happened yet.
 */
class SessionKeepAliveTest {

    private class FakeStore(private var token: String? = "the-token") : SessionCredentialStore {
        var cleared = false
        val stored: String? get() = token
        override val isLoggedIn: Boolean get() = token != null

        /** Null = never told. Distinguishes "adopted an empty list" from "not called". */
        var stations: List<StationAccess>? = null
            private set
        var swaggerEnabled: Boolean? = null
            private set

        override suspend fun refreshFromSession(stations: List<StationAccess>, swaggerEnabled: Boolean) {
            this.stations = stations
            this.swaggerEnabled = swaggerEnabled
        }

        fun clear() {
            token = null
            cleared = true
        }
    }

    private val seen = mutableListOf<String>()

    /** Wired exactly as production does it (coreModule): the CLIENT owns the 401 policy. */
    private fun keepAlive(store: FakeStore, engine: MockEngine) = SessionKeepAlive(
        session = store,
        api = ApiHttpClient(
            tokenProvider = { store.stored },
            onUnauthorized = { store.clear() },
            stationProvider = { null },
            engine = engine,
            logging = false,
            baseUrlProvider = { "http://server" },
        ),
    )

    private fun serving(expiresInSeconds: Long?) = MockEngine { request ->
        seen += request.url.encodedPath
        respond(
            """{"expiresInSeconds":${expiresInSeconds ?: "null"}}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun failing(status: HttpStatusCode) = MockEngine { request ->
        seen += request.url.encodedPath
        respondError(status)
    }

    /** The keep-alive's reply as the server actually sends it: lifetime AND stations. */
    private fun servingStations(vararg ids: String) = MockEngine { request ->
        seen += request.url.encodedPath
        val stations = ids.joinToString(",") {
            """{"id":"$it","name":"$it","role":"NORMAL_USER"}"""
        }
        respond(
            """{"expiresInSeconds":3600,"stations":[$stations]}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    /**
     * THE FIX for "a station migrated in after sign-in stays invisible".
     *
     * The station list used to arrive exactly once - in the login reply - and nothing
     * ever refreshed it. A group added afterwards was hosted, granted and reachable by
     * the API, yet absent from the dropdown until the operator logged OUT and back in;
     * restarting the client only re-read the same stale snapshot from storage. The
     * knock is the one thing a logged-in, idle client does on its own, so it is where
     * the list has to come home.
     */
    @Test
    fun knock_adopts_the_servers_current_station_list() = runTest {
        val store = FakeStore()

        keepAlive(store, servingStations("crete-tv", "radio-984", "test-tv")).knock()

        assertEquals(
            listOf("crete-tv", "radio-984", "test-tv"),
            store.stations?.map { it.id },
        )
    }

    /**
     * The Swagger toggle rides the SAME knock as the station list, so a client that
     * skipped login (persisted token) tracks a server.yaml `swagger` change without
     * re-login - the exact staleness a login-only flag would leave behind.
     */
    @Test
    fun knock_adopts_the_servers_swagger_flag() = runTest {
        val store = FakeStore()
        val engine = MockEngine { request ->
            seen += request.url.encodedPath
            respond(
                """{"expiresInSeconds":3600,"stations":[],"swaggerEnabled":true}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        keepAlive(store, engine).knock()

        assertEquals(true, store.swaggerEnabled)
    }

    /**
     * A knock that did NOT get through is not evidence that the user lost their
     * stations. Adopting the empty list a failed call carries would empty the dropdown
     * of a working client every time the server blinked - the same class of bug as
     * logging someone out on a network hiccup, which this whole type exists to avoid.
     */
    @Test
    fun a_failed_knock_leaves_the_station_list_untouched() = runTest {
        val store = FakeStore()

        keepAlive(store, failing(HttpStatusCode.ServiceUnavailable)).knock()

        assertNull(store.stations)
    }

    /**
     * The gap this whole class exists to close: an app open and IDLE makes no
     * requests, so its window is never slid and its token ages out ON SCREEN.
     * Reaching the endpoint at all is what slides it - the reply only paces us.
     */
    @Test
    fun `a knock touches the session endpoint and paces the next one`() = runTest {
        val store = FakeStore()

        val next = keepAlive(store, serving(expiresInSeconds = 3600)).knock()

        assertEquals(listOf("/api/auth/session"), seen)
        assertEquals(15.minutes, next, "knock at a quarter of the lifetime")
    }

    /**
     * THE ONE THAT MATTERS. The server is restarting, or we are in a tunnel. The
     * session is perfectly good - it must survive untouched, and we try again soon.
     */
    @Test
    fun `a network failure does NOT log the user out`() = runTest {
        val store = FakeStore()

        val next = keepAlive(store, failing(HttpStatusCode.ServiceUnavailable)).knock()

        assertNull(next, "no answer, so no schedule - the caller retries soon")
        assertEquals("the-token", store.stored, "a server briefly down must not cost a session")
        assertFalse(store.cleared, "only a 401 may end a session")
        assertTrue(store.isLoggedIn)
    }

    /** Starting up with a token that already lapsed. The one logout that IS allowed. */
    @Test
    fun `a 401 ends the session`() = runTest {
        val store = FakeStore(token = "lapsed-token")

        keepAlive(store, failing(HttpStatusCode.Unauthorized)).knock()

        assertTrue(store.cleared, "a lapsed token cannot be renewed - the user signs in again")
        assertFalse(store.isLoggedIn)
    }

    /**
     * THE MULTI-TAB / MULTI-INSTANCE INVARIANT, and the reason the keep-alive
     * renews the LIFETIME and never the TOKEN.
     *
     * The token belongs to the STORE, and on the web every browser tab shares it
     * (same origin, same storage) while caching its own copy in memory. If a knock
     * came back with a NEW token, the tab that knocked would save it, the server
     * would retire the old value, and every OTHER tab would still be holding a
     * corpse: 401, clear the shared store, and now they are all logged out. Opening
     * a second tab would sign you out of both. Two desktop instances, likewise.
     *
     * So: the token the app started with is the token it still has afterwards.
     */
    @Test
    fun `a knock NEVER changes the token - other tabs are holding it too`() = runTest {
        val store = FakeStore(token = "shared-across-tabs")
        // Even if the server volunteered one, we must not take it.
        val engine = MockEngine { request ->
            seen += request.url.encodedPath
            respond(
                """{"token":"rotated","expiresInSeconds":3600}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        keepAlive(store, engine).knock()

        assertEquals("shared-across-tabs", store.stored, "rotating a shared token logs every other tab out")
        assertFalse(store.cleared)
    }

    /** `expiration: false` on the server: nothing lapses, so knock at the cap - never spin. */
    @Test
    fun `a never-expiring token knocks at the six-hour cap`() = runTest {
        assertEquals(6.hours, keepAlive(FakeStore(), serving(expiresInSeconds = null)).knock())
    }

    /** A 90-day lifetime would put a quarter of it 22 days out. Cap it - knocks are nearly free. */
    @Test
    // No comma in the name: Apple targets reject one in a backticked identifier,
    // and it fails the iOS test COMPILE, not just the run.
    fun `a long lifetime is capped rather than trusted blindly`() = runTest {
        val ninetyDays = 90L * 24 * 3600
        assertEquals(6.hours, keepAlive(FakeStore(), serving(ninetyDays)).knock())
    }

    /** A deliberately tiny `days:` (testing) must not turn the loop into a spin. */
    @Test
    fun `a tiny lifetime is floored at a minute`() = runTest {
        assertEquals(1.minutes, keepAlive(FakeStore(), serving(expiresInSeconds = 20)).knock())
    }
}

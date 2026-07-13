package eu.anifantakis.commercials.core.data.session

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.core.domain.util.DataResult
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The slice of [AuthSession] that [SessionKeepAlive] needs.
 *
 * A seam, not ceremony: [AuthSession] is built on KSafe, which reaches real
 * platform storage (Keychain, Keystore, DPAPI, browser storage) and cannot be
 * faked. Depending on this instead is what makes the keep-alive testable at all.
 */
interface SessionCredentialStore {
    val isLoggedIn: Boolean
}

/**
 * KEEPS A RUNNING APP LOGGED IN. The client half of the server's `session:` policy.
 *
 * ── The gap this closes ──
 *
 * A token's window is pushed forward BY USE: `AuthDb.findUserByToken` slides it on
 * every authenticated request. That covers an app that is being clicked - but an
 * app left OPEN and IDLE makes no requests at all, so its token quietly ages and
 * DIES ON SCREEN. The next click, hours into the working day, would 401 and throw
 * the user out to Login. That is the one place a session may never be lost.
 *
 * So a running app knocks: `GET /api/auth/session`, every [Companion.MAX_INTERVAL]
 * or a quarter of the lifetime, whichever is sooner. The endpoint does nothing;
 * merely passing the bearer auth to reach it slides the window. It is one indexed
 * lookup, and the server only writes past the token's half-life - a 90-day session
 * costs a handful of UPDATEs a year no matter how often we knock.
 *
 * ── It renews the LIFETIME, never the token ──
 *
 * Nothing here mints a new token, and that is deliberate. The token belongs to the
 * STORE, and every live client of that store shares it. Rotate it in one and the
 * others are holding a corpse.
 *
 * On the web that is the ordinary case, not an edge case: a second TAB. Same
 * origin, same browser storage, but each tab caches the token in its own memory.
 * Tab B rotates, the server retires the old value, and Tab A - still holding it -
 * 401s, clears the SHARED store, and takes Tab B down with it on its next read.
 * Opening a second tab would log you out of both. (And on the web "app start" is
 * every page load, so it would fire on every refresh.) Two desktop instances would
 * do the same.
 *
 * Sliding the window touches no client but the one that knocked. Every tab keeps
 * the same token, every tab knocks, and they all slide the same row.
 *
 * ── The one logout that is allowed ──
 *
 * Starting the app with a token that ALREADY lapsed: it 401s, [ApiHttpClient]
 * clears the session, and the user signs in. Renewal happens strictly BEFORE
 * expiry, never after - a credential able to revive an EXPIRED session would BE
 * the session, and would never expire. The lifetime therefore measures how long
 * the app may be CLOSED, not how long it may be open.
 *
 * ── What it will not survive ──
 *
 * A machine ASLEEP (or a browser tab FROZEN) past the whole lifetime. No timer
 * runs, so nothing knocks; on wake the overdue knock fires at once, 401s, and the
 * user logs in. Honest, and the dial that fixes it is `session.days` in
 * server.yaml - it must comfortably exceed the longest sleep you expect. At the
 * default 90 days, nothing real comes close.
 */
class SessionKeepAlive(
    private val session: SessionCredentialStore,
    private val api: ApiHttpClient,
) {

    private companion object {
        /**
         * Knock at a QUARTER of the lifetime, so two knocks in a row can be missed
         * (a sleep, a flaky network, a throttled background tab) and the session
         * still lives. Capped, because knocks are nearly free and a 90-day window
         * would otherwise put a quarter of it 22 days away. Floored, so a
         * deliberately tiny `days:` (testing) cannot spin the loop.
         */
        val MAX_INTERVAL = 6.hours
        val MIN_INTERVAL = 1.minutes

        /** A knock that failed on the NETWORK (not a 401): come back soon, stay logged in. */
        val RETRY_INTERVAL = 1.minutes
    }

    /**
     * Runs until cancelled. Call from a coroutine tied to "a session exists":
     * logging out cancels it, logging in starts a new one.
     *
     * The first knock is immediate - on the web that means every page load, which
     * is exactly right: a returning client proves its token is still alive and
     * slides the window, all in one cheap GET.
     *
     * Never throws, and - crucially - never logs anyone out by itself. A 401 is the
     * ONLY thing that ends a session, and [ApiHttpClient] already owns that policy
     * (clear + bounce to Login). Everything else here is a hiccup: a server briefly
     * down must leave the user exactly where they were.
     */
    suspend fun run() {
        while (session.isLoggedIn) {
            val next = knock() ?: RETRY_INTERVAL
            delay(next)
        }
    }

    /**
     * One knock. Returns the interval to the next one, or null if it did not get
     * through - in which case we do NOTHING. A 401 has already been handled for us
     * (the session is cleared, the UI is on its way to Login); anything else is the
     * network, and the stored token is still perfectly good.
     */
    internal suspend fun knock(): Duration? =
        when (val result = api.get<SessionInfoDto>("/api/auth/session")) {
            is DataResult.Success -> result.data.expiresInSeconds.toInterval()
            is DataResult.Failure -> null
        }

    /**
     * NULL seconds means the server's policy is `expiration: false` - the token
     * never lapses and there is nothing to keep alive. Knock at the cap anyway
     * rather than stopping: it costs one request every six hours, and it means an
     * admin can turn expiry back ON in server.yaml without every long-running
     * client having to be restarted to notice.
     */
    private fun Long?.toInterval(): Duration =
        (this?.seconds?.div(4) ?: MAX_INTERVAL).coerceIn(MIN_INTERVAL, MAX_INTERVAL)
}

/** The server's SessionInfoResponse (`GET /api/auth/session`). */
@Serializable
private data class SessionInfoDto(val expiresInSeconds: Long? = null)

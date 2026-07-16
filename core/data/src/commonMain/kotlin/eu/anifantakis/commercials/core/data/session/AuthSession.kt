package eu.anifantakis.commercials.core.data.session

import eu.anifantakis.commercials.core.data.preferences.platformAwaitReady
import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.commercials.core.domain.auth.StationAccess
import eu.anifantakis.commercials.core.domain.auth.UserSession
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.invoke
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Provided

/**
 * Everything the client knows about the logged-in user, persisted as ONE
 * encrypted KSafe entry. Empty token == logged out.
 */
@Serializable
data class StoredSession(
    val token: String = "",
    val displayName: String = "",
    /** Config-managed super administrator (may manage users). */
    val isAdmin: Boolean = false,
    /** Server-wide: whether the server serves the Swagger UI (server.yaml `swagger`). */
    val swaggerEnabled: Boolean = false,
    val stations: List<StationAccess> = emptyList(),
    val selectedStationId: String = "",
    /** Logged in with a temp password: the app traps the user on a mandatory
     *  change-password screen until they set their own. */
    val mustChangePassword: Boolean = false,
)

/**
 * The app-wide auth state: an encrypted, persisted [StoredSession] (survives
 * restarts, so a returning user goes straight in) plus an observable revision so
 * UI and ViewModels react to login/logout/station switches.
 *
 * ── ONE credential, renewed - not an access/refresh pair ──
 *
 * The token has a lifetime (server.yaml `session:` - 90 days, sliding, by default)
 * and it is renewed, but there is no second secret and there must not be one.
 * [SessionKeepAlive] rotates this token at launch and beats while the app runs, so
 * a session can only lapse while the app is CLOSED. A LAPSED token cannot be
 * renewed - which is exactly what keeps the lifetime meaningful.
 *
 * The access/refresh pair exists to rescue STATELESS tokens: a JWT cannot be
 * revoked, so it must be made to die fast, so a second long-lived token is needed
 * to avoid re-prompting. Ours is a row in `auth_tokens` - revocation is a DELETE
 * and the very next request 401s. There is no damage window to bound, so there is
 * nothing for a short-lived access token to buy, and a second secret would only
 * add another thing that can fail to persist (see `store()` below for how badly the
 * first one already can) plus the rotation race that is the pattern's classic bug.
 *
 * Expiry policy belongs to the server; the client holds one credential, keeps it
 * warm, and reacts to 401 (`ApiHttpClient` -> `clear()` -> Login).
 *
 * Role and customer scoping are PER STATION: [role]/[clientCode] always
 * reflect the currently [selectedStation], so switching stations can turn an
 * editor into a viewer and vice versa.
 *
 * Koin singleton - inject it, never construct it directly.
 */
// KSafe is @Provided: it comes from the expect/actual factory (createKSafe),
// registered with a classic-DSL definition the compile-time checker can't
// index - the annotation tells the checker to trust it exists at runtime.
class AuthSession(@Provided private val ksafe: KSafe) : UserSession, SessionCredentialStore {

    private companion object {
        const val SESSION_KEY = "session_v2"
    }

    // Encrypted at rest by default (KSafe). Explicit versioned key: the
    // session shape changed with multi-tenancy (per-station access list), and
    // a v1 blob under the old key must not be decoded into the new class -
    // returning users just log in once more.
    //
    // The delegate is kept for READS and for the non-critical writes (station
    // switch, logout). The critical write - login - goes through the awaited
    // `store()` below, under this SAME key. They must never drift apart.
    private var stored by ksafe(StoredSession(), key = SESSION_KEY)

    /**
     * Bumped on every login/logout/station switch. A StateFlow, so both the
     * chrome (collectAsState) and ViewModels (collect) can react - and this
     * data-layer class stays free of any UI framework.
     */
    private val _revision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = _revision.asStateFlow()

    /**
     * Must be awaited once at startup BEFORE the first session read. Required
     * on the browser targets (WebCrypto decrypts the KSafe cache
     * asynchronously); no-op everywhere else.
     */
    suspend fun ready() {
        ksafe.platformAwaitReady()
    }

    override val isLoggedIn: Boolean get() = stored.token.isNotEmpty()
    val token: String? get() = stored.token.ifEmpty { null }
    override val displayName: String get() = stored.displayName
    override val isAdmin: Boolean get() = stored.isAdmin
    override val swaggerEnabled: Boolean get() = stored.swaggerEnabled

    /** True until the temp-password user sets their own; drives the mandatory
     *  change-password trap in the navigation root. */
    val mustChangePassword: Boolean get() = stored.mustChangePassword

    /** All stations this user may access, in server order. */
    override val stations: List<StationAccess> get() = stored.stations

    /** The station the UI is currently showing (first one as a safe default). */
    override val selectedStation: StationAccess?
        get() = stored.stations.firstOrNull { it.id == stored.selectedStationId }
            ?: stored.stations.firstOrNull()

    /** The user's role ON THE SELECTED STATION. */
    override val role: AppRole get() = AppRole.parse(selectedStation?.role ?: "")

    /** Customer scoping code ON THE SELECTED STATION (null unless customer viewer). */
    val clientCode: String? get() = selectedStation?.clientCode

    /**
     * Encrypted storage is BROKEN here: a session written now would be rolled
     * straight back out again. The preflight that lets us refuse a login BEFORE
     * asking for a password, rather than after.
     *
     * Today this means one thing - a browser page that is not a secure context
     * (plain HTTP from anything but localhost), where the browser withholds
     * `crypto.subtle` outright and every encrypted write throws.
     *
     * ── The question this asks, and the one it does NOT ──
     *
     * `isEncryptionOperational` answers only "will an encrypted write succeed?".
     * It is deliberately NOT `effectiveLevel != intendedLevel`, which asks the
     * different question "is this as strong as I hoped?" - and legitimately
     * answers "no" where everything works fine:
     *
     *   - a JVM desktop with no OS keyring (headless Linux, or an opt-out)
     *     reports SANDBOX_PROTECTED -> SOFTWARE and encrypts perfectly well;
     *   - the iOS Simulator without a Keychain entitlement reports
     *     HARDWARE_BACKED -> SOFTWARE and encrypts perfectly well.
     *
     * Gate on strength and you refuse to log in on every Simulator run and on any
     * Linux box without gnome-keyring. Gate on operability and you refuse only
     * where the write would actually be lost.
     *
     * The list of non-operational conditions lives inside KSafe, so a future one
     * arrives here for free - which is why this reads the flag instead of
     * matching a note string by hand.
     */
    val encryptionUnavailable: Boolean
        get() = !ksafe.protectionInfo.isEncryptionOperational

    /**
     * Persists the session and AWAITS the write. Throws if it could not be
     * written - the caller must not enter an authenticated state.
     *
     * ── Why this one write is awaited and the others are not ──
     *
     * The `stored` delegate's setter is `putDirectRaw`: it hands the value to a
     * background write channel with no completion handle, so an encryption
     * failure is delivered to NOBODY. It is only logged. There is no error flow,
     * no callback, and the batch runs inside a `runCatching`, so not even a
     * global uncaught handler sees it.
     *
     * That is exactly what happened on a page served over plain HTTP: the browser
     * withholds `crypto.subtle` outside a secure context, every encrypted write
     * fails, KSafe ROLLS THE VALUE BACK (it is evicted from the cache, not left
     * stale) - and the app carried on into the authenticated screens with a
     * session that had already evaporated. The next API call 401s, the session
     * clears, and the user is bounced to Login with no explanation. A silent loop.
     *
     * `put()` is the only path that surfaces it: it passes a completion Deferred
     * and awaits it, so the failure is thrown into this coroutine. Login is the
     * one write where a silent failure is unacceptable, so login is the one write
     * that waits. Station switches and logout keep the fire-and-forget delegate.
     *
     * DO NOT "verify" this by reading the value back. A failed write can still
     * read back as present through a read-write Compose holder, whose write-echo
     * guard waits for a disk emission that never comes. The exception is the only
     * honest signal.
     */
    suspend fun store(
        token: String,
        displayName: String,
        isAdmin: Boolean,
        stations: List<StationAccess>,
        mustChangePassword: Boolean = false,
        swaggerEnabled: Boolean = false,
    ) {
        val session = StoredSession(
            token = token,
            displayName = displayName,
            isAdmin = isAdmin,
            swaggerEnabled = swaggerEnabled,
            stations = stations,
            selectedStationId = stations.firstOrNull()?.id ?: "",
            mustChangePassword = mustChangePassword,
        )
        // Refuse here too, not only at the caller's preflight: a session written
        // where encryption cannot run is a session that is already gone, and no
        // future caller should be able to route around that by forgetting to ask.
        check(!encryptionUnavailable) {
            "Encrypted storage is not operational (${ksafe.protectionInfo.custody}) - " +
                "the session would not persist."
        }
        // Same key and same (default, Encrypted) mode as the delegate, so the
        // delegate's reads see this write - it is the same KSafe core and cache.
        ksafe.put(SESSION_KEY, session)
        _revision.value++
    }

    /**
     * Adopts the server's CURRENT session facts - the station list AND the Swagger
     * toggle - on every keep-alive knock, without touching the token or the
     * identity. The first knock is at launch, so a returning client that skipped
     * login (persisted token) still tracks a server.yaml `swagger` change.
     *
     * This is what makes a station added AFTER sign-in appear: the list used to be
     * written once, at login, and nothing ever refreshed it - so a group migrated
     * in was reachable by the API and invisible in the dropdown until the operator
     * logged out and back in.
     *
     * The SELECTED station survives if it is still granted; otherwise the
     * selection falls back to the first one, because a UI pointed at a station the
     * user can no longer reach would 403 on every request. No-op when nothing
     * changed, so the revision does not churn on every keep-alive tick.
     */
    override suspend fun refreshFromSession(stations: List<StationAccess>, swaggerEnabled: Boolean) {
        if (!isLoggedIn) return
        if (stations == stored.stations && swaggerEnabled == stored.swaggerEnabled) return

        val keepSelected = stations.any { it.id == stored.selectedStationId }
        val session = stored.copy(
            stations = stations,
            swaggerEnabled = swaggerEnabled,
            selectedStationId =
                if (keepSelected) stored.selectedStationId else stations.firstOrNull()?.id ?: "",
        )
        // Persist, so a client restart does not fall back to the stale snapshot -
        // which is exactly the bug this fixes.
        ksafe.put(SESSION_KEY, session)
        _revision.value++
    }

    /** Switches the active station (no-op for ids the user has no grant on). */
    override fun selectStation(stationId: String) {
        if (stored.stations.none { it.id == stationId }) return
        if (stored.selectedStationId == stationId) return
        stored = stored.copy(selectedStationId = stationId)
        _revision.value++
    }

    /**
     * Logout / 401. IDEMPOTENT: if there is no session, do nothing - do NOT bump
     * the revision. A 401 handler calls this, and a revision bump makes every
     * session observer re-run; an observer that refetches (Timetable) would 401
     * again (no token), clear again, bump again - an infinite loop that spams the
     * "session expired" snackbar. Bumping only on a real transition breaks it.
     */
    fun clear() {
        if (!isLoggedIn) return
        stored = StoredSession()
        _revision.value++
    }
}

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
    val stations: List<StationAccess> = emptyList(),
    val selectedStationId: String = "",
)

/**
 * The app-wide auth state: an encrypted, persisted [StoredSession] (survives
 * restarts - tokens never expire, so a returning user goes straight in) plus
 * an observable revision so UI and ViewModels react to login/logout/station switches.
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
class AuthSession(@Provided private val ksafe: KSafe) : UserSession {

    // Encrypted at rest by default (KSafe). Explicit versioned key: the
    // session shape changed with multi-tenancy (per-station access list), and
    // a v1 blob under the old key must not be decoded into the new class -
    // returning users just log in once more.
    private var stored by ksafe(StoredSession(), key = "session_v2")

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

    val isLoggedIn: Boolean get() = stored.token.isNotEmpty()
    val token: String? get() = stored.token.ifEmpty { null }
    override val displayName: String get() = stored.displayName
    override val isAdmin: Boolean get() = stored.isAdmin

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

    fun store(token: String, displayName: String, isAdmin: Boolean, stations: List<StationAccess>) {
        stored = StoredSession(
            token = token,
            displayName = displayName,
            isAdmin = isAdmin,
            stations = stations,
            selectedStationId = stations.firstOrNull()?.id ?: ""
        )
        _revision.value++
    }

    /** Switches the active station (no-op for ids the user has no grant on). */
    override fun selectStation(stationId: String) {
        if (stored.stations.none { it.id == stationId }) return
        if (stored.selectedStationId == stationId) return
        stored = stored.copy(selectedStationId = stationId)
        _revision.value++
    }

    fun clear() {
        stored = StoredSession()
        _revision.value++
    }
}

package eu.anifantakis.commercials.core.data.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.anifantakis.commercials.core.data.preferences.platformAwaitReady
import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.invoke
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Provided

/**
 * One station this user may access, as granted by the server: the station's
 * id/display name plus THIS user's role on it (and, for customer viewers,
 * the client code their data is scoped to).
 */
@Serializable
data class StationAccess(
    val id: String,
    val name: String,
    val role: String,
    val clientCode: String? = null,
)

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
 * a Compose-observable revision so UI reacts to login/logout/station switches.
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
class AuthSession(@Provided private val ksafe: KSafe) {

    // Encrypted at rest by default (KSafe). Explicit versioned key: the
    // session shape changed with multi-tenancy (per-station access list), and
    // a v1 blob under the old key must not be decoded into the new class -
    // returning users just log in once more.
    private var stored by ksafe(StoredSession(), key = "session_v2")

    /**
     * Bumped on every login/logout/station switch. Composables that read
     * [revision] (or any property below AFTER reading it) recompose when the
     * session changes.
     */
    var revision by mutableStateOf(0)
        private set

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
    val displayName: String get() = stored.displayName
    val isAdmin: Boolean get() = stored.isAdmin

    /** All stations this user may access, in server order. */
    val stations: List<StationAccess> get() = stored.stations

    /** The station the UI is currently showing (first one as a safe default). */
    val selectedStation: StationAccess?
        get() = stored.stations.firstOrNull { it.id == stored.selectedStationId }
            ?: stored.stations.firstOrNull()

    /** The user's role ON THE SELECTED STATION. */
    val role: AppRole get() = AppRole.parse(selectedStation?.role ?: "")

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
        revision++
    }

    /** Switches the active station (no-op for ids the user has no grant on). */
    fun selectStation(stationId: String) {
        if (stored.stations.none { it.id == stationId }) return
        if (stored.selectedStationId == stationId) return
        stored = stored.copy(selectedStationId = stationId)
        revision++
    }

    fun clear() {
        stored = StoredSession()
        revision++
    }
}

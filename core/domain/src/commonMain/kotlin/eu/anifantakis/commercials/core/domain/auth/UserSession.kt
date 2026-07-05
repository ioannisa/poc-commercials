package eu.anifantakis.commercials.core.domain.auth

/**
 * The logged-in user's session as the UI consumes it: read-only session facts
 * plus the single action the chrome may trigger (switch the active station).
 *
 * This is the DOMAIN contract the presentation layer depends on. The concrete,
 * token-holding, KSafe-persisted implementation (`AuthSession`) lives in
 * :core:data and is bound to this interface by Koin - so presentation depends
 * on an abstraction in `domain`, never on the data class (DIP). Deliberately
 * narrow (ISP): the token and the login/logout/store mutations stay off this
 * surface - only the app-assembly layer (which may see :core:data) touches
 * those.
 */
interface UserSession {
    /**
     * Bumped on every login/logout/station switch. Read it in a composable
     * (before the properties below) to make that composable recompose when the
     * session changes.
     */
    val revision: Int
    val displayName: String
    /** Config-managed super administrator (may manage users). */
    val isAdmin: Boolean
    /** The user's role ON THE CURRENTLY SELECTED STATION. */
    val role: AppRole
    /** All stations this user may access, in server order. */
    val stations: List<StationAccess>
    /** The station the UI is currently showing. */
    val selectedStation: StationAccess?
    /** Switch the active station (no-op for ids the user has no grant on). */
    fun selectStation(stationId: String)
}

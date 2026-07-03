package eu.anifantakis.ctv.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.invoke
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Provided

/**
 * Everything the client knows about the logged-in user, persisted as ONE
 * encrypted KSafe entry. Empty token == logged out.
 */
@Serializable
data class StoredSession(
    val token: String = "",
    val role: String = "",
    val displayName: String = "",
    val clientCode: String? = null,
)

/**
 * The app-wide auth state: an encrypted, persisted [StoredSession] (survives
 * restarts - tokens never expire, so a returning user goes straight in) plus
 * a Compose-observable revision so UI reacts to login/logout.
 *
 * Koin singleton - inject it, never construct it directly.
 */
// KSafe is @Provided: it comes from the expect/actual factory (createKSafe),
// registered with a classic-DSL definition the compile-time checker can't
// index - the annotation tells the checker to trust it exists at runtime.
class AuthSession(@Provided private val ksafe: KSafe) {

    // Encrypted at rest by default (KSafe); key = property name.
    private var stored by ksafe(StoredSession())

    /**
     * Bumped on every login/logout. Composables that read [revision] (or any
     * property below AFTER reading it) recompose when the session changes.
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
    val role: AppRole get() = AppRole.parse(stored.role)
    val displayName: String get() = stored.displayName
    val clientCode: String? get() = stored.clientCode

    fun store(token: String, role: String, displayName: String, clientCode: String?) {
        stored = StoredSession(token, role, displayName, clientCode)
        revision++
    }

    fun clear() {
        stored = StoredSession()
        revision++
    }
}

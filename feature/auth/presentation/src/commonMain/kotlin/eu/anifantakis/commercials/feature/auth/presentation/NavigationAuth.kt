package eu.anifantakis.commercials.feature.auth.presentation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.anifantakis.commercials.feature.auth.presentation.screens.login.LoginScreenRoot
import kotlinx.serialization.Serializable

@Serializable
sealed interface AuthNavType : NavKey {
    @Serializable
    data object Login : AuthNavType
}

/**
 * Cross-feature transitions (where to go after login) come in as callbacks -
 * routes of other features are not visible from here.
 */
fun EntryProviderScope<NavKey>.authEntries(
    onLoggedIn: () -> Unit,
) {
    entry<AuthNavType.Login> {
        LoginScreenRoot(onLoggedIn = onLoggedIn)
    }
}

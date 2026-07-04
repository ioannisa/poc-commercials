package eu.anifantakis.commercials.feature.preferences.presentation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.anifantakis.commercials.core.presentation.navigation.Navigator
import eu.anifantakis.commercials.feature.preferences.presentation.screens.preferences.PreferencesScreenRoot
import kotlinx.serialization.Serializable

@Serializable
sealed interface PreferencesNavType : NavKey {
    @Serializable
    data object Preferences : PreferencesNavType
}

/**
 * Account dialogs belong to :feature:auth and the maintenance screens are
 * separate features - all of them arrive as callbacks the app layer wires.
 */
fun EntryProviderScope<NavKey>.preferencesEntries(
    navigator: Navigator,
    isAdmin: () -> Boolean,
    onChangePassword: () -> Unit,
    onRecoveryCodes: () -> Unit,
    onManageUsers: () -> Unit,
    onMigration: () -> Unit,
    onDatabases: () -> Unit,
) {
    entry<PreferencesNavType.Preferences> {
        PreferencesScreenRoot(
            isAdmin = isAdmin(),
            onBack = { navigator.goBack() },
            onChangePassword = onChangePassword,
            onRecoveryCodes = onRecoveryCodes,
            onManageUsers = onManageUsers,
            onMigration = onMigration,
            onDatabases = onDatabases,
        )
    }
}

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
    swaggerEnabled: () -> Boolean,
    aiChatEnabled: () -> Boolean,
    onChangePassword: () -> Unit,
    onApiTokens: () -> Unit,
    onAdminMcp: () -> Unit,
    onAppUpdate: () -> Unit,
    onManageUsers: () -> Unit,
    onMigration: () -> Unit,
    onDatabases: () -> Unit,
    onOpenSwagger: () -> Unit,
    onAiChat: () -> Unit,
) {
    entry<PreferencesNavType.Preferences> {
        PreferencesScreenRoot(
            isAdmin = isAdmin(),
            swaggerEnabled = swaggerEnabled(),
            aiChatEnabled = aiChatEnabled(),
            onBack = { navigator.goBack() },
            onChangePassword = onChangePassword,
            onApiTokens = onApiTokens,
            onAdminMcp = onAdminMcp,
            onAppUpdate = onAppUpdate,
            onManageUsers = onManageUsers,
            onMigration = onMigration,
            onDatabases = onDatabases,
            onOpenSwagger = onOpenSwagger,
            onAiChat = onAiChat,
        )
    }
}

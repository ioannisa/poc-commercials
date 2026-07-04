package eu.anifantakis.commercials.feature.user_management.presentation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.anifantakis.commercials.core.presentation.navigation.Navigator
import eu.anifantakis.commercials.feature.user_management.presentation.user_management.UserManagementScreenRoot
import kotlinx.serialization.Serializable

@Serializable
sealed interface UserManagementNavType : NavKey {
    @Serializable
    data object UserManagement : UserManagementNavType
}

fun EntryProviderScope<NavKey>.userManagementEntries(
    navigator: Navigator,
    stationChoices: () -> List<Pair<String, String>>,
) {
    entry<UserManagementNavType.UserManagement> {
        UserManagementScreenRoot(
            stationChoices = stationChoices(),
            onBack = { navigator.goBack() },
        )
    }
}

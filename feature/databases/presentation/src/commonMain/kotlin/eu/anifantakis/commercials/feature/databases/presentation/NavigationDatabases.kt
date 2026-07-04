package eu.anifantakis.commercials.feature.databases.presentation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.anifantakis.commercials.core.presentation.navigation.Navigator
import eu.anifantakis.commercials.feature.databases.presentation.databases.DatabasesScreenRoot
import kotlinx.serialization.Serializable

@Serializable
sealed interface DatabasesNavType : NavKey {
    @Serializable
    data object Databases : DatabasesNavType
}

fun EntryProviderScope<NavKey>.databasesEntries(
    navigator: Navigator,
) {
    entry<DatabasesNavType.Databases> {
        DatabasesScreenRoot(onBack = { navigator.goBack() })
    }
}

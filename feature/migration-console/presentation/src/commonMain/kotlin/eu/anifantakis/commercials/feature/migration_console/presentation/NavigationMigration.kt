package eu.anifantakis.commercials.feature.migration_console.presentation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.anifantakis.commercials.core.presentation.navigation.Navigator
import eu.anifantakis.commercials.feature.migration_console.presentation.migration.MigrationScreenRoot
import kotlinx.serialization.Serializable

@Serializable
sealed interface MigrationNavType : NavKey {
    @Serializable
    data object Migration : MigrationNavType
}

fun EntryProviderScope<NavKey>.migrationEntries(
    navigator: Navigator,
) {
    entry<MigrationNavType.Migration> {
        MigrationScreenRoot(onBack = { navigator.goBack() })
    }
}

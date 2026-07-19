package eu.anifantakis.commercials.feature.galaxy_bridge.presentation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.anifantakis.commercials.core.presentation.navigation.Navigator
import eu.anifantakis.commercials.feature.galaxy_bridge.presentation.screens.galaxy_bridge.GalaxyBridgeScreenRoot
import kotlinx.serialization.Serializable

@Serializable
sealed interface GalaxyBridgeNavType : NavKey {
    @Serializable
    data object GalaxyBridge : GalaxyBridgeNavType
}

fun EntryProviderScope<NavKey>.galaxyBridgeEntries(
    navigator: Navigator,
) {
    entry<GalaxyBridgeNavType.GalaxyBridge> {
        GalaxyBridgeScreenRoot(onBack = { navigator.goBack() })
    }
}

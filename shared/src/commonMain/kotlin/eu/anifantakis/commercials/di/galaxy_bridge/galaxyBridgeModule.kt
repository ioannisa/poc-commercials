package eu.anifantakis.commercials.di.galaxy_bridge

import eu.anifantakis.commercials.feature.galaxy_bridge.data.GalaxyBridgeRepositoryImpl
import eu.anifantakis.commercials.feature.galaxy_bridge.data.data_source.RemoteGalaxyBridgeDataSourceImpl
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.GalaxyBridgeRepository
import eu.anifantakis.commercials.feature.galaxy_bridge.domain.data_source.RemoteGalaxyBridgeDataSource
import eu.anifantakis.commercials.feature.galaxy_bridge.presentation.screens.galaxy_bridge.GalaxyBridgeViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val galaxyBridgeModule = module {
    singleOf(::RemoteGalaxyBridgeDataSourceImpl).bind<RemoteGalaxyBridgeDataSource>()
    singleOf(::GalaxyBridgeRepositoryImpl).bind<GalaxyBridgeRepository>()
    viewModelOf(::GalaxyBridgeViewModel)
}

package eu.anifantakis.commercials.di.migration_console

import eu.anifantakis.commercials.feature.migration_console.data.MigrationRepositoryImpl
import eu.anifantakis.commercials.feature.migration_console.data.data_source.RemoteMigrationDataSourceImpl
import eu.anifantakis.commercials.feature.migration_console.domain.MigrationRepository
import eu.anifantakis.commercials.feature.migration_console.domain.data_source.RemoteMigrationDataSource
import eu.anifantakis.commercials.feature.migration_console.presentation.screens.migration.MigrationViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val migrationConsoleModule = module {
    singleOf(::RemoteMigrationDataSourceImpl).bind<RemoteMigrationDataSource>()
    singleOf(::MigrationRepositoryImpl).bind<MigrationRepository>()
    viewModelOf(::MigrationViewModel)
}

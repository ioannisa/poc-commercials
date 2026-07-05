package eu.anifantakis.commercials.di.databases

import eu.anifantakis.commercials.feature.databases.data.DatabasesRepositoryImpl
import eu.anifantakis.commercials.feature.databases.data.data_source.RemoteDatabasesDataSourceImpl
import eu.anifantakis.commercials.feature.databases.domain.DatabasesRepository
import eu.anifantakis.commercials.feature.databases.domain.data_source.RemoteDatabasesDataSource
import eu.anifantakis.commercials.feature.databases.presentation.screens.databases.DatabasesViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val databasesModule = module {
    singleOf(::RemoteDatabasesDataSourceImpl).bind<RemoteDatabasesDataSource>()
    singleOf(::DatabasesRepositoryImpl).bind<DatabasesRepository>()
    viewModelOf(::DatabasesViewModel)
}

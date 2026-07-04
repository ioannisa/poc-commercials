package eu.anifantakis.commercials.di.user_management

import eu.anifantakis.commercials.feature.user_management.data.UserManagementRepositoryImpl
import eu.anifantakis.commercials.feature.user_management.domain.UserManagementRepository
import eu.anifantakis.commercials.feature.user_management.presentation.screens.user_management.UserManagementViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val userManagementModule = module {
    singleOf(::UserManagementRepositoryImpl).bind<UserManagementRepository>()
    viewModelOf(::UserManagementViewModel)
}

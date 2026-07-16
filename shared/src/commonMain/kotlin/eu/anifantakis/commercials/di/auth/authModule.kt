package eu.anifantakis.commercials.di.auth

import eu.anifantakis.commercials.feature.auth.data.AuthRepositoryImpl
import eu.anifantakis.commercials.feature.auth.data.data_source.RemoteAuthDataSourceImpl
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.domain.data_source.RemoteAuthDataSource
import eu.anifantakis.commercials.feature.auth.presentation.screens.api_tokens.ApiTokensViewModel
import eu.anifantakis.commercials.feature.auth.presentation.screens.change_password.ChangePasswordViewModel
import eu.anifantakis.commercials.feature.auth.presentation.screens.login.LoginViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val authModule = module {
    singleOf(::RemoteAuthDataSourceImpl).bind<RemoteAuthDataSource>()
    singleOf(::AuthRepositoryImpl).bind<AuthRepository>()
    viewModelOf(::LoginViewModel)
    viewModelOf(::ChangePasswordViewModel)
    viewModelOf(::ApiTokensViewModel)
}

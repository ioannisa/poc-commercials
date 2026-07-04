package eu.anifantakis.commercials.di.auth

import eu.anifantakis.commercials.feature.auth.data.AuthRepositoryImpl
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.presentation.screens.change_password.ChangePasswordViewModel
import eu.anifantakis.commercials.feature.auth.presentation.screens.login.LoginViewModel
import eu.anifantakis.commercials.feature.auth.presentation.screens.recovery_codes.RecoveryCodesViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val authModule = module {
    singleOf(::AuthRepositoryImpl).bind<AuthRepository>()
    viewModelOf(::LoginViewModel)
    viewModelOf(::ChangePasswordViewModel)
    viewModelOf(::RecoveryCodesViewModel)
}

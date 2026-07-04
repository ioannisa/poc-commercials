package eu.anifantakis.commercials.di.preferences

import eu.anifantakis.commercials.feature.preferences.data.KSafeUserPreferences
import eu.anifantakis.commercials.feature.preferences.domain.UserPreferences
import eu.anifantakis.commercials.feature.preferences.presentation.screens.preferences.PreferencesViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val preferencesModule = module {
    singleOf(::KSafeUserPreferences).bind<UserPreferences>()
    viewModelOf(::PreferencesViewModel)
}

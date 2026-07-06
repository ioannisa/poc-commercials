package eu.anifantakis.commercials.di.core

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.core.data.network.PlainJsonHttpClient
import eu.anifantakis.commercials.core.data.party_search.PartySearchRepositoryImpl
import eu.anifantakis.commercials.core.data.party_search.data_source.RemotePartySearchDataSourceImpl
import eu.anifantakis.commercials.core.data.preferences.KSafeAppLanguageStore
import eu.anifantakis.commercials.core.data.preferences.createKSafe
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.domain.auth.UserSession
import eu.anifantakis.commercials.core.domain.party_search.PartySearchRepository
import eu.anifantakis.commercials.core.domain.party_search.data_source.RemotePartySearchDataSource
import eu.anifantakis.commercials.core.domain.preferences.AppLanguageStore
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** App-lifetime infrastructure shared by every feature. */
val coreModule = module {
    // Factory call, not a constructor - classic lambda definition. KSafe
    // consumers mark the parameter @Provided.
    single { createKSafe() }

    // DIP: presentation injects the domain UserSession contract; the data
    // layer (ApiHttpClient below) still gets the concrete AuthSession.
    singleOf(::AuthSession).bind<UserSession>()

    // App-wide MVI container (kmp-developer global state)
    single { GlobalStateContainer() }

    // App language: ONE persisted KSafe entry behind the domain AppLanguageStore
    // seam; the global LocalizationManager attaches it at startup (App.kt).
    singleOf(::KSafeAppLanguageStore).bind<AppLanguageStore>()

    // ONE client per backend personality (CommonHttpClient subclasses):
    // authenticated + station-stamped for the app API, plain for login/recovery
    single { ApiHttpClient(session = get<AuthSession>()) }
    single { PlainJsonHttpClient() }

    // Master-data party search (used by timetable finder + schedule email)
    singleOf(::RemotePartySearchDataSourceImpl).bind<RemotePartySearchDataSource>()
    singleOf(::PartySearchRepositoryImpl).bind<PartySearchRepository>()
}

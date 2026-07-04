package eu.anifantakis.commercials.di.core

import eu.anifantakis.commercials.core.data.party_search.PartySearchRepositoryImpl
import eu.anifantakis.commercials.core.data.preferences.createKSafe
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.domain.party_search.PartySearchRepository
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** App-lifetime infrastructure shared by every feature. */
val coreModule = module {
    // Factory call, not a constructor - classic lambda definition. KSafe
    // consumers mark the parameter @Provided.
    single { createKSafe() }

    singleOf(::AuthSession)

    // App-wide MVI container (kmp-developer global state)
    single { GlobalStateContainer() }

    // Master-data party search (used by timetable finder + schedule email)
    singleOf(::PartySearchRepositoryImpl).bind<PartySearchRepository>()
}

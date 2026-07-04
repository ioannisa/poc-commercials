package eu.anifantakis.commercials.di.timetable

import eu.anifantakis.commercials.feature.timetable.data.FinderRepositoryImpl
import eu.anifantakis.commercials.feature.timetable.data.PlacementsRepositoryImpl
import eu.anifantakis.commercials.feature.timetable.data.ScheduleRepositoryImpl
import eu.anifantakis.commercials.feature.timetable.domain.FinderRepository
import eu.anifantakis.commercials.feature.timetable.domain.PlacementsRepository
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail.CommercialDetailViewModel
import eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable.TimetableViewModel
import eu.anifantakis.commercials.feature.timetable.presentation.store.ScheduleCellsStore
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val timetableModule = module {
    singleOf(::ScheduleRepositoryImpl).bind<ScheduleRepository>()
    singleOf(::PlacementsRepositoryImpl).bind<PlacementsRepository>()
    singleOf(::FinderRepositoryImpl).bind<FinderRepository>()

    // the ONE shared piece between the grid and the detail screen
    single { ScheduleCellsStore() }

    viewModelOf(::TimetableViewModel)
    viewModel { params ->
        CommercialDetailViewModel(
            breakId = params.get(),
            date = params.get(),
            placementsRepository = get(),
            store = get(),
        )
    }
}

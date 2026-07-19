package eu.anifantakis.commercials.di.timetable

import eu.anifantakis.commercials.feature.timetable.data.FinderRepositoryImpl
import eu.anifantakis.commercials.feature.timetable.data.KSafeTimetablePreferences
import eu.anifantakis.commercials.feature.timetable.data.data_source.RemoteFinderDataSourceImpl
import eu.anifantakis.commercials.feature.timetable.data.data_source.RemotePlacementsDataSourceImpl
import eu.anifantakis.commercials.feature.timetable.data.data_source.RemoteProgramsDataSourceImpl
import eu.anifantakis.commercials.feature.timetable.data.data_source.RemoteScheduleDataSourceImpl
import eu.anifantakis.commercials.feature.timetable.data.PlacementsRepositoryImpl
import eu.anifantakis.commercials.feature.timetable.data.ProgramsRepositoryImpl
import eu.anifantakis.commercials.feature.timetable.data.ScheduleRepositoryImpl
import eu.anifantakis.commercials.feature.timetable.domain.FinderRepository
import eu.anifantakis.commercials.feature.timetable.domain.TimetablePreferences
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemoteFinderDataSource
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemotePlacementsDataSource
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemoteProgramsDataSource
import eu.anifantakis.commercials.feature.timetable.domain.data_source.RemoteScheduleDataSource
import eu.anifantakis.commercials.feature.timetable.domain.PlacementsRepository
import eu.anifantakis.commercials.feature.timetable.domain.ProgramsRepository
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommonViewModel
import eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail.CommercialDetailViewModel
import eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable.TimetableViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val timetableModule = module {
    // wire side (data sources) + organizers (repositories)
    singleOf(::RemoteScheduleDataSourceImpl).bind<RemoteScheduleDataSource>()
    singleOf(::RemotePlacementsDataSourceImpl).bind<RemotePlacementsDataSource>()
    singleOf(::RemoteProgramsDataSourceImpl).bind<RemoteProgramsDataSource>()
    singleOf(::RemoteFinderDataSourceImpl).bind<RemoteFinderDataSource>()
    singleOf(::ScheduleRepositoryImpl).bind<ScheduleRepository>()
    singleOf(::PlacementsRepositoryImpl).bind<PlacementsRepository>()
    singleOf(::ProgramsRepositoryImpl).bind<ProgramsRepository>()
    singleOf(::FinderRepositoryImpl).bind<FinderRepository>()
    singleOf(::KSafeTimetablePreferences).bind<TimetablePreferences>()

    // the flow-shared ViewModel: cells + all placement I/O. Resolved by the
    // nav entries against the timetable flow's ViewModelStoreOwner and
    // handed to the per-screen ViewModels below via parametersOf.
    viewModelOf(::TimetableCommonViewModel)

    viewModel { params ->
        TimetableViewModel(
            finderRepository = get(),
            partySearch = get(),
            scheduleRepository = get(),
            programsRepository = get(),
            common = params.get(),
            prefs = get(),
            session = get(),
            reportService = get(),
            logoCache = get(),
            refreshBus = get(),
            screenContext = get(),
        )
    }
    viewModel { params ->
        CommercialDetailViewModel(
            time = params.get(),
            date = params.get(),
            common = params.get(),
            session = get(),
            reportService = get(),
            logoCache = get(),
        )
    }
}

package eu.anifantakis.commercials.di.schedule_email

import eu.anifantakis.commercials.feature.schedule_email.data.ScheduleEmailRepositoryImpl
import eu.anifantakis.commercials.feature.schedule_email.data.data_source.RemoteScheduleEmailDataSourceImpl
import eu.anifantakis.commercials.feature.schedule_email.domain.ScheduleEmailRepository
import eu.anifantakis.commercials.feature.schedule_email.domain.data_source.RemoteScheduleEmailDataSource
import eu.anifantakis.commercials.feature.schedule_email.presentation.screens.email_preview.EmailPreviewViewModel
import eu.anifantakis.commercials.feature.schedule_email.presentation.screens.send_schedule_email.SendScheduleEmailViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val scheduleEmailModule = module {
    singleOf(::RemoteScheduleEmailDataSourceImpl).bind<RemoteScheduleEmailDataSource>()
    singleOf(::ScheduleEmailRepositoryImpl).bind<ScheduleEmailRepository>()
    viewModelOf(::SendScheduleEmailViewModel)
    viewModel { params -> EmailPreviewViewModel(request = params.get(), repository = get()) }
}

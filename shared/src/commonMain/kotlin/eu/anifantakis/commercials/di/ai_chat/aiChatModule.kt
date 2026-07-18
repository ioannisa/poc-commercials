package eu.anifantakis.commercials.di.ai_chat

import eu.anifantakis.commercials.feature.ai_chat.data.AiChatRepositoryImpl
import eu.anifantakis.commercials.feature.ai_chat.data.data_source.RemoteAiChatDataSourceImpl
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatRepository
import eu.anifantakis.commercials.feature.ai_chat.domain.data_source.RemoteAiChatDataSource
import eu.anifantakis.commercials.feature.ai_chat.presentation.screens.ai_chat.AiChatViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val aiChatModule = module {
    singleOf(::RemoteAiChatDataSourceImpl).bind<RemoteAiChatDataSource>()
    singleOf(::AiChatRepositoryImpl).bind<AiChatRepository>()
    viewModelOf(::AiChatViewModel)
}

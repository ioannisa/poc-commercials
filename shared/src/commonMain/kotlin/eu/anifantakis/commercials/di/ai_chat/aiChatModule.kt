package eu.anifantakis.commercials.di.ai_chat

import eu.anifantakis.commercials.feature.ai_chat.data.AiChatRepositoryImpl
import eu.anifantakis.commercials.feature.ai_chat.data.KSafeAiChatHistoryStore
import eu.anifantakis.commercials.feature.ai_chat.data.KSafeAiChatPreferences
import eu.anifantakis.commercials.feature.ai_chat.data.data_source.RemoteAiChatDataSourceImpl
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatHistoryStore
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatPreferences
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
    singleOf(::KSafeAiChatPreferences).bind<AiChatPreferences>()
    singleOf(::KSafeAiChatHistoryStore).bind<AiChatHistoryStore>()
    viewModelOf(::AiChatViewModel)
}

package eu.anifantakis.commercials.di

import eu.anifantakis.commercials.di.ai_chat.aiChatModule
import eu.anifantakis.commercials.di.auth.authModule
import eu.anifantakis.commercials.di.core.coreModule
import eu.anifantakis.commercials.di.databases.databasesModule
import eu.anifantakis.commercials.di.galaxy_bridge.galaxyBridgeModule
import eu.anifantakis.commercials.di.migration_console.migrationConsoleModule
import eu.anifantakis.commercials.di.preferences.preferencesModule
import eu.anifantakis.commercials.di.schedule_email.scheduleEmailModule
import eu.anifantakis.commercials.di.timetable.timetableModule
import eu.anifantakis.commercials.di.user_management.userManagementModule
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.mp.KoinPlatformTools

/** Per-platform bindings: ReportService (desktop engine / browser API / unsupported). */
expect val platformModule: Module

/** Everything [initKoin] loads - also what KoinGraphTest verifies. */
val allModules: List<Module>
    get() = listOf(
        coreModule,
        authModule,
        aiChatModule,
        timetableModule,
        scheduleEmailModule,
        preferencesModule,
        userManagementModule,
        migrationConsoleModule,
        galaxyBridgeModule,
        databasesModule,
        platformModule,
    )

/**
 * Starts Koin with the app's modules. Safe to call more than once (e.g.
 * Android Activity recreation) - subsequent calls are no-ops.
 */
fun initKoin(config: KoinAppDeclaration? = null) {
    if (KoinPlatformTools.defaultContext().getOrNull() != null) return
    startKoin {
        config?.invoke(this)
        modules(allModules)
    }
}

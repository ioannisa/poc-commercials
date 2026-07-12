package eu.anifantakis.commercials

import androidx.compose.ui.window.ComposeUIViewController
import eu.anifantakis.commercials.core.data.config.AppConfig
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.di.initKoin
import kotlinx.coroutines.runBlocking
import org.koin.mp.KoinPlatform

fun MainViewController(): platform.UIKit.UIViewController {
    initKoin()
    runBlocking {
        AppConfig.load()
        KoinPlatform.getKoin().get<AuthSession>().ready()
    }
    return ComposeUIViewController { App() }
}

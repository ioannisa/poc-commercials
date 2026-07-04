package eu.anifantakis.commercials.core.data.preferences

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.commercials.core.data.config.AndroidAppContext

actual fun createKSafe(): KSafe {
    val context = AndroidAppContext.appContext
        ?: error("AndroidAppContext.init(context) must be called before using AuthSession")
    return KSafe(context)
}

internal actual suspend fun KSafe.platformAwaitReady() { /* preloads synchronously */ }

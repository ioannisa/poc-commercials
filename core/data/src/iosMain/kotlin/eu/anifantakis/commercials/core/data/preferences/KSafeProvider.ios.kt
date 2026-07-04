package eu.anifantakis.commercials.core.data.preferences

import eu.anifantakis.lib.ksafe.KSafe

actual fun createKSafe(): KSafe = KSafe()

internal actual suspend fun KSafe.platformAwaitReady() { /* preloads synchronously */ }

package eu.anifantakis.poc.ctv.auth

import eu.anifantakis.lib.ksafe.KSafe

internal actual fun createKSafe(): KSafe = KSafe()

internal actual suspend fun KSafe.platformAwaitReady() { /* preloads synchronously */ }

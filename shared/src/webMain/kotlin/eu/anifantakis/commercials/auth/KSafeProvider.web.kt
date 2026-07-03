package eu.anifantakis.commercials.auth

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeConfig
import eu.anifantakis.lib.ksafe.awaitCacheReady

internal actual fun createKSafe(): KSafe =
    KSafe(config = KSafeConfig(appNamespace = "eu.anifantakis.commercials"))

internal actual suspend fun KSafe.platformAwaitReady() = awaitCacheReady()

package eu.anifantakis.commercials.core.data.preferences

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeConfig
import eu.anifantakis.lib.ksafe.awaitCacheReady

actual fun createKSafe(): KSafe =
    KSafe(config = KSafeConfig(appNamespace = "eu.anifantakis.commercials"))

internal actual suspend fun KSafe.platformAwaitReady() = awaitCacheReady()

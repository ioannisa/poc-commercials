package eu.anifantakis.commercials.core.data.preferences

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeConfig

// Desktop OS secret stores are per-OS-user (shared across apps), so namespace
// the key entries to this application.
actual fun createKSafe(): KSafe =
    KSafe(config = KSafeConfig(appNamespace = "eu.anifantakis.commercials"))

internal actual suspend fun KSafe.platformAwaitReady() { /* preloads synchronously */ }

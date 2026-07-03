package eu.anifantakis.commercials.auth

import eu.anifantakis.lib.ksafe.KSafe

/**
 * The app's single KSafe instance (encrypted key/value store). Created once,
 * lazily, by [AuthSession] - never construct additional instances for the
 * same store (see the KSafe docs on instance singletons).
 *
 * Android needs the application context (provided via AndroidAppContext,
 * which MainActivity initializes before the UI); every other platform
 * constructs without arguments.
 */
internal expect fun createKSafe(): KSafe

/**
 * Awaits the KSafe cache. `awaitCacheReady` exists only on the web targets
 * (WebCrypto decrypts asynchronously); JVM/Android/iOS preload synchronously,
 * so their actuals are no-ops.
 */
internal expect suspend fun KSafe.platformAwaitReady()

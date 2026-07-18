package eu.anifantakis.commercials.core.data.session

import eu.anifantakis.commercials.core.domain.auth.BrowserCredential
import eu.anifantakis.commercials.core.domain.auth.BrowserCredentials

/**
 * The platform's [BrowserCredentials]. Real only on wasmJs (the shipped web
 * client), where it bridges the Credential Management API; everywhere else -
 * desktop/Android/iOS, and the compile-only js target - it is [NoopBrowserCredentials]:
 * those platforms have no browser password manager to talk to.
 */
expect fun createBrowserCredentials(): BrowserCredentials

/** Store-nothing/know-nothing stand-in for the non-web platforms. */
internal object NoopBrowserCredentials : BrowserCredentials {
    override suspend fun store(username: String, password: String) = Unit
    override suspend fun retrieve(): BrowserCredential? = null
}

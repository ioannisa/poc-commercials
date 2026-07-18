package eu.anifantakis.commercials.core.data.session

import eu.anifantakis.commercials.core.domain.auth.BrowserCredentials

actual fun createBrowserCredentials(): BrowserCredentials = NoopBrowserCredentials

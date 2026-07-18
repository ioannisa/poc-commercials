package eu.anifantakis.commercials.core.data.session

import eu.anifantakis.commercials.core.domain.auth.BrowserCredentials

// The js target compiles for completeness but is not a shipped product
// (platform scope: desktop + wasmJs) - it gets the no-op, not the interop.
actual fun createBrowserCredentials(): BrowserCredentials = NoopBrowserCredentials

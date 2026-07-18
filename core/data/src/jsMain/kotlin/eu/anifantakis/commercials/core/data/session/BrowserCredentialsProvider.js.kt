package eu.anifantakis.commercials.core.data.session

import eu.anifantakis.commercials.core.domain.auth.BrowserCredential
import eu.anifantakis.commercials.core.domain.auth.BrowserCredentials
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/*
 * Credential Management API bridge for the js (Kotlin/JS) target. This target
 * isn't shipped (platform scope: desktop + wasmJs), but it is a browser where
 * the API exists, so it carries the real implementation, not a no-op - full
 * parity with the wasmJs actual.
 *
 * Kotlin/JS interop differs from Kotlin/Wasm's: `dynamic` reads the credential
 * fields directly and Kotlin lambdas drop straight into `Promise.then` - no
 * callback-into-js() bridge needed. `PasswordCredential` is Chromium-only, so
 * every entry point feature-detects and degrades to a no-op/null. Kotlin
 * identifiers are kept OUT of the js() strings (the JS compiler may mangle
 * them); values cross the boundary through `dynamic` members instead.
 */

// Constructed only after PasswordCredential is confirmed to exist, so declaring
// it external is safe even where the browser lacks it.
private external class PasswordCredential(init: dynamic)

private fun jsNavigator(): dynamic = js("navigator")

private fun passwordCredentialSupported(): Boolean =
    js("typeof PasswordCredential === 'function'") as Boolean

internal class WebBrowserCredentials : BrowserCredentials {

    override suspend fun store(username: String, password: String) {
        if (!passwordCredentialSupported()) return
        val creds = jsNavigator().credentials
        if (creds == null || creds.store == null) return
        try {
            val init: dynamic = js("({})")
            init.id = username
            init.password = password
            // Fire-and-forget: the promise only tracks the browser's own save
            // bubble, which is none of the app's business.
            creds.store(PasswordCredential(init))
        } catch (e: Throwable) {
            // user dismissed the prompt, or saving is disabled - nothing to do
        }
    }

    override suspend fun retrieve(): BrowserCredential? = suspendCoroutine { cont ->
        val creds = if (passwordCredentialSupported()) jsNavigator().credentials else null
        if (creds == null || creds.get == null) {
            cont.resume(null)
            return@suspendCoroutine
        }
        val options: dynamic = js("({ password: true, mediation: 'optional' })")
        creds.get(options).then(
            { c: dynamic ->
                cont.resume(
                    if (c != null && c.type == "password" && c.password != null)
                        BrowserCredential(c.id as String, c.password as String)
                    else null
                )
                null
            },
            { _: dynamic ->
                cont.resume(null)
                null
            },
        )
    }
}

actual fun createBrowserCredentials(): BrowserCredentials = WebBrowserCredentials()

package eu.anifantakis.commercials.core.data.session

import eu.anifantakis.commercials.core.domain.auth.BrowserCredential
import eu.anifantakis.commercials.core.domain.auth.BrowserCredentials
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/*
 * Credential Management API bridge. `PasswordCredential` is Chromium-only, so
 * every entry point feature-detects and degrades to a no-op/null - Firefox and
 * Safari users simply keep typing. Requires a secure context, which the app
 * always has (https at the tunnel edge; localhost counts as secure in dev).
 *
 * Kotlin/Wasm interop rules force these to be top-level functions with
 * compile-time-constant `js()` bodies.
 */

private fun jsStoreCredential(id: String, password: String): Unit = js(
    """{
        if (typeof PasswordCredential === 'function' && navigator.credentials && navigator.credentials.store) {
            try {
                navigator.credentials.store(new PasswordCredential({ id: id, password: password }));
            } catch (e) { /* user dismissed or manager disabled - nothing to do */ }
        }
    }"""
)

private fun jsGetCredential(onResult: (String?, String?) -> Unit): Unit = js(
    """{
        var done = false;
        var finish = function (u, p) { if (!done) { done = true; onResult(u, p); } };
        if (!(typeof PasswordCredential === 'function' && navigator.credentials && navigator.credentials.get)) {
            finish(null, null);
            return;
        }
        try {
            navigator.credentials.get({ password: true, mediation: 'optional' }).then(
                function (c) {
                    if (c && c.type === 'password' && c.password) finish(c.id, c.password);
                    else finish(null, null);
                },
                function (e) { finish(null, null); }
            );
        } catch (e) { finish(null, null); }
    }"""
)

internal class WebBrowserCredentials : BrowserCredentials {

    override suspend fun store(username: String, password: String) {
        // Fire-and-forget: the returned promise only tracks the browser's own
        // save bubble, which is none of the app's business.
        jsStoreCredential(username, password)
    }

    override suspend fun retrieve(): BrowserCredential? = suspendCoroutine { cont ->
        jsGetCredential { username, password ->
            cont.resume(
                if (username != null && password != null) BrowserCredential(username, password)
                else null
            )
        }
    }
}

actual fun createBrowserCredentials(): BrowserCredentials = WebBrowserCredentials()

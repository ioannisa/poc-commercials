package eu.anifantakis.commercials.core.domain.auth

/** A username+password pair held by the browser's password manager. */
data class BrowserCredential(val username: String, val password: String)

/**
 * The browser password manager as the presentation layer consumes it - a DIP
 * seam over the web Credential Management API (:core:data), so ViewModels stay
 * fake-testable and no feature module touches JS interop.
 *
 * The app renders on a canvas (Compose), so Chrome never sees a real login
 * `<form>` and its save/autofill heuristics can't engage - this seam is the
 * replacement: [store] after a successful login raises the browser's own
 * "Save password?" bubble, [retrieve] asks it back at the login screen.
 *
 * Web-only by nature: every other platform (and non-Chromium browsers, where
 * `PasswordCredential` doesn't exist) is a silent no-op / null.
 */
interface BrowserCredentials {
    /** Offer the credential to the browser's password manager (fire-and-forget). */
    suspend fun store(username: String, password: String)

    /** A saved credential, or null (nothing saved, dialog dismissed, unsupported). */
    suspend fun retrieve(): BrowserCredential?
}

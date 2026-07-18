package eu.anifantakis.commercials.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.install
import io.ktor.server.plugins.origin

/**
 * Browser-side hardening headers on every response. Non-browser clients
 * (desktop app, MCP connectors, curl) ignore them; they exist for the web
 * client and swagger pages this server serves on a public hostname.
 *
 * Each header is appended only when the response does not already carry it -
 * the OAuth pages set their own, stricter set (see OAuthRoutes).
 *
 * HSTS is keyed on the request's effective scheme: behind the TLS-terminating
 * proxy XForwardedHeaders resolves it to "https" and browsers pin the host to
 * HTTPS; plain http://localhost never sees the header, so local development
 * stays unpinned. Deliberately no includeSubDomains and no preload - the pin
 * must not spill onto sibling subdomains or outlive this deployment.
 */
val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    val hstsValue = "max-age=${180L * 24 * 3600}"   // 180 days
    on(ResponseBodyReadyForSend) { call, _ ->
        val headers = call.response.headers
        fun appendIfAbsent(name: String, value: String) {
            if (headers[name] == null) headers.append(name, value)
        }
        appendIfAbsent("X-Content-Type-Options", "nosniff")
        appendIfAbsent("Referrer-Policy", "no-referrer")
        // The web app is never embedded in another site (owner's decision,
        // 2026-07-18): X-Frame-Options for legacy browsers, frame-ancestors
        // for current ones. A CSP carrying only frame-ancestors does not
        // restrict the page's own scripts/styles/images.
        appendIfAbsent("X-Frame-Options", "DENY")
        appendIfAbsent("Content-Security-Policy", "frame-ancestors 'none'")
        if (call.request.origin.scheme == "https") {
            appendIfAbsent("Strict-Transport-Security", hstsValue)
        }
    }
}

fun Application.configureSecurityHeaders() {
    install(SecurityHeaders)
}

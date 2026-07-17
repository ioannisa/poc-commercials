package eu.anifantakis.commercials.server.oauth

import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/**
 * PKCE (RFC 7636) and redirect-URI validation - pure functions, unit-tested
 * against the RFC's Appendix B vector.
 */

/** RFC 7636 §4.1: 43-128 characters of `[A-Za-z0-9-._~]` (the unreserved set). */
private val CODE_VERIFIER_FORMAT = Regex("[A-Za-z0-9\\-._~]{43,128}")

/** Hosts that count as loopback for RFC 8252 §7.3 (native-app redirects). */
private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

/** `BASE64URL-nopadding(SHA-256(verifier))` per RFC 7636 §4.2. */
fun s256Challenge(codeVerifier: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(codeVerifier.encodeToByteArray())
    )

fun isValidCodeVerifier(verifier: String): Boolean = CODE_VERIFIER_FORMAT.matches(verifier)

/**
 * Whether [challenge] matches [verifier] under S256 - constant-time, so the
 * comparison itself leaks nothing (defence-in-depth; the values are one-shot).
 */
fun challengeMatches(challenge: String, verifier: String): Boolean =
    MessageDigest.isEqual(s256Challenge(verifier).encodeToByteArray(), challenge.encodeToByteArray())

/**
 * A redirect URI acceptable at REGISTRATION time: `https` anywhere, or `http`
 * on a loopback host only (native apps, RFC 8252). Custom schemes -
 * `javascript:`, `data:`, app schemes - are rejected outright; fragments are
 * forbidden by RFC 6749 §3.1.2.
 */
fun isAcceptableRedirectUri(uri: String): Boolean {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
    if (parsed.fragment != null) return false
    return when (parsed.scheme?.lowercase()) {
        "https" -> parsed.host != null
        "http" -> parsed.host?.lowercase() in LOOPBACK_HOSTS
        else -> false
    }
}

/**
 * Whether [presented] matches [registered] - EXACT string match (RFC 8252 /
 * OAuth 2.1: no prefix, no substring, no wildcard), with the ONE permitted
 * variance: on loopback hosts the PORT is ignored (RFC 8252 §7.3 - native
 * clients like Claude Code and GitHub Copilot CLI bind an ephemeral localhost
 * port per run).
 */
fun redirectUriMatches(registered: String, presented: String): Boolean {
    if (registered == presented) return true
    val reg = runCatching { URI(registered) }.getOrNull() ?: return false
    val pres = runCatching { URI(presented) }.getOrNull() ?: return false
    val host = reg.host?.lowercase() ?: return false
    if (host !in LOOPBACK_HOSTS) return false
    return reg.scheme.equals(pres.scheme, ignoreCase = true) &&
        host == pres.host?.lowercase() &&
        reg.path == pres.path &&
        reg.query == pres.query &&
        pres.fragment == null
}

/**
 * Canonical resource form per RFC 8707 as MCP clients send it: lowercase
 * scheme+host, default port dropped, no trailing slash, no fragment. Used to
 * compare a presented `resource` parameter against the URIs this server
 * actually protects - never byte-compared.
 */
fun canonicalResource(uri: String): String? {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase() ?: return null
    val host = parsed.host?.lowercase() ?: return null
    val port = when {
        parsed.port == -1 -> ""
        scheme == "http" && parsed.port == 80 -> ""
        scheme == "https" && parsed.port == 443 -> ""
        else -> ":${parsed.port}"
    }
    val path = parsed.path.orEmpty().trimEnd('/')
    return "$scheme://$host$port$path"
}

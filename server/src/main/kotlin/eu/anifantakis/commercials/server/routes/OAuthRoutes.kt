package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.server.auth.AuthDb
import eu.anifantakis.commercials.server.auth.OAuthDb
import eu.anifantakis.commercials.server.oauth.canonicalResource
import eu.anifantakis.commercials.server.oauth.challengeMatches
import eu.anifantakis.commercials.server.oauth.isAcceptableRedirectUri
import eu.anifantakis.commercials.server.oauth.isValidCodeVerifier
import eu.anifantakis.commercials.server.oauth.redirectUriMatches
import eu.anifantakis.commercials.server.oauth.renderAuthorizePage
import eu.anifantakis.commercials.server.oauth.renderOAuthErrorPage
import eu.anifantakis.commercials.server.stations.StationRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * The built-in OAuth 2.1 Authorization Server: RFC 9728/8414 discovery, RFC
 * 7591 dynamic client registration, authorization-code + PKCE (S256 only)
 * with the app's own users as the accounts, rotating refresh tokens, and RFC
 * 7009 revocation.
 *
 * Mounted OPEN (no bearer) and ONLY when `publicBaseUrl` is set in
 * server.yaml - it is the issuer, and every advertised endpoint URL derives
 * from it. Native MCP connectors (claude.ai, ChatGPT, Gemini, ...) discover
 * this AS from the 401 challenge on /mcp/http (see McpAuthChallenge) or the
 * well-known documents, register themselves, and send the user through
 * /oauth/authorize - where they log in with their existing Commercials
 * Manager credentials. The minted access token resolves through
 * `AuthDb.findUserByToken` like any other bearer, so per-station grants and
 * roles apply to the MCP tools unchanged.
 *
 * NO CSRF token on the authorize form, by decision: this server sets no
 * cookies anywhere, so there is no ambient credential to ride - the form
 * demands the user's password on every submission, which doubles as
 * consent-per-connection (mandatory for open-DCR clients: an auto-consent
 * keyed on a client_id anyone can mint would be an account-takeover
 * primitive).
 */
fun Route.oAuthRoutes(oauthDb: OAuthDb, authDb: AuthDb, registry: StationRegistry) {
    val issuer = registry.publicBaseUrl ?: return   // routes exist only with a public base URL

    /** The canonical RFC 8707 `resource` values this server accepts. */
    val acceptedResources = setOfNotNull(
        canonicalResource(issuer),
        canonicalResource("$issuer/mcp"),
        canonicalResource("$issuer/mcp/http"),
    )

    // ───────────────────────────────────────────── discovery documents ──

    /**
     * RFC 8414 authorization-server metadata. Field notes, each one earned:
     * - `code_challenge_methods_supported` MUST be present - spec-compliant
     *   clients refuse to proceed without it.
     * - `scopes_supported` MUST include `offline_access` - Claude requests a
     *   refresh token only when it sees it advertised.
     * - `none` in the auth methods = public clients (claude.ai registers as
     *   one); the secret methods serve operator-registered clients (Gemini
     *   Enterprise, M365 federated, Copilot Studio manual mode).
     */
    val asMetadata: JsonObject = buildJsonObject {
        put("issuer", issuer)
        put("authorization_endpoint", "$issuer/oauth/authorize")
        put("token_endpoint", "$issuer/oauth/token")
        put("registration_endpoint", "$issuer/oauth/register")
        put("revocation_endpoint", "$issuer/oauth/revoke")
        put("response_types_supported", buildJsonArray { add("code") })
        put("grant_types_supported", buildJsonArray { add("authorization_code"); add("refresh_token") })
        put("code_challenge_methods_supported", buildJsonArray { add("S256") })
        put(
            "token_endpoint_auth_methods_supported",
            buildJsonArray { add("none"); add("client_secret_post"); add("client_secret_basic") }
        )
        put("scopes_supported", buildJsonArray { add("offline_access") })
    }

    fun protectedResourceMetadata(resource: String): JsonObject = buildJsonObject {
        put("resource", resource)
        put("authorization_servers", buildJsonArray { add(issuer) })
        put("bearer_methods_supported", buildJsonArray { add("header") })
    }

    // The AS metadata is served under BOTH well-known names (the MCP spec
    // mandates dual discovery: RFC 8414 first, then OIDC discovery - Gemini
    // CLI, Amazon Q and others exercise the OIDC path) and under the
    // path-suffixed variants clients derive from the MCP resource path.
    val asMetadataPaths = listOf("oauth-authorization-server", "openid-configuration").flatMap { name ->
        listOf("/.well-known/$name", "/.well-known/$name/mcp", "/.well-known/$name/mcp/http")
    }
    for (path in asMetadataPaths) {
        /**
         * Serve the OAuth authorization-server metadata (RFC 8414 / OIDC discovery alias).
         *
         * Tag: MCP
         */
        get(path) {
            call.respond(asMetadata)
        }
    }

    /**
     * Serve the RFC 9728 protected-resource metadata for the streamable MCP endpoint.
     *
     * Tag: MCP
     */
    get("/.well-known/oauth-protected-resource/mcp/http") {
        call.respond(protectedResourceMetadata("$issuer/mcp/http"))
    }

    /**
     * Serve the RFC 9728 protected-resource metadata for the legacy SSE MCP endpoint.
     *
     * Tag: MCP
     */
    get("/.well-known/oauth-protected-resource/mcp") {
        call.respond(protectedResourceMetadata("$issuer/mcp"))
    }

    /**
     * Serve the RFC 9728 protected-resource metadata (root fallback; primary resource).
     *
     * Tag: MCP
     */
    get("/.well-known/oauth-protected-resource") {
        call.respond(protectedResourceMetadata("$issuer/mcp/http"))
    }

    // ──────────────────────────────────────────── client registration ──

    /**
     * Register an OAuth client (RFC 7591 dynamic client registration). Open by
     * design - claude.ai/ChatGPT self-register - and safe to leave open:
     * registration grants NOTHING (the user's password is still required at
     * /oauth/authorize), redirect URIs are restricted to https/loopback, and
     * stale never-authorized clients are pruned. A `client_secret` is always
     * returned (M365 hard-requires one; public clients ignore it).
     *
     * Tag: MCP
     */
    post("/oauth/register") {
        val req = runCatching { call.receive<DcrRequest>() }.getOrNull()
            ?: return@post call.respondDcrError("invalid_client_metadata", "Body must be a JSON registration request")

        if (req.redirect_uris.isEmpty()) {
            return@post call.respondDcrError("invalid_redirect_uri", "redirect_uris must be a non-empty array")
        }
        if (req.redirect_uris.size > MAX_REDIRECT_URIS) {
            return@post call.respondDcrError("invalid_redirect_uri", "Too many redirect_uris (max $MAX_REDIRECT_URIS)")
        }
        req.redirect_uris.forEach { uri ->
            if (uri.length > MAX_REDIRECT_URI_LENGTH || !isAcceptableRedirectUri(uri)) {
                return@post call.respondDcrError(
                    "invalid_redirect_uri",
                    "redirect_uris must be https, or http on a loopback host, without fragments"
                )
            }
        }
        val method = req.token_endpoint_auth_method ?: "none"
        if (method !in ALLOWED_TOKEN_AUTH_METHODS) {
            return@post call.respondDcrError(
                "invalid_client_metadata",
                "token_endpoint_auth_method must be one of $ALLOWED_TOKEN_AUTH_METHODS"
            )
        }
        val clientName = (req.client_name ?: "MCP Client")
            .filter { it.code >= 0x20 }   // strip control characters before it ever reaches a page
            .take(255)
            .ifBlank { "MCP Client" }

        val registered = withContext(Dispatchers.IO) {
            oauthDb.registerClient(clientName, req.redirect_uris, method)
        }
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(
            HttpStatusCode.Created,
            buildJsonObject {
                put("client_id", registered.clientId)
                put("client_secret", registered.clientSecret)
                put("client_secret_expires_at", 0)   // 0 = never expires (RFC 7591)
                put("client_id_issued_at", System.currentTimeMillis() / 1000)
                put("client_name", clientName)
                put("redirect_uris", buildJsonArray { req.redirect_uris.forEach { add(it) } })
                put("token_endpoint_auth_method", method)
                put("grant_types", buildJsonArray { add("authorization_code"); add("refresh_token") })
                put("response_types", buildJsonArray { add("code") })
            }
        )
    }

    // ──────────────────────────────────────────────────── authorize ──

    /**
     * Render the login + consent page for an authorization-code request
     * (OAuth 2.1: PKCE S256 required, `code` response type only). An unknown
     * client or an unregistered redirect URI gets an ERROR PAGE, never a
     * redirect - redirecting to an unvalidated URI is the open-redirect bug.
     *
     * Tag: MCP
     */
    get("/oauth/authorize") {
        val p = call.request.queryParameters
        val client = call.validateClientAndRedirect(oauthDb, p) ?: return@get
        val redirectUri = p["redirect_uri"]!!   // non-null past validation
        val state = p["state"]

        val failure = validateAuthorizeParams(p, acceptedResources)
        if (failure != null) {
            return@get call.authorizeErrorRedirect(redirectUri, failure.first, failure.second, state, issuer)
        }
        if (!withContext(Dispatchers.IO) { authDb.isMcpEnabled() }) {
            return@get call.respondHtmlPage(
                renderOAuthErrorPage("MCP access disabled", "MCP/API access is currently disabled on this server. Contact your administrator."),
                HttpStatusCode.ServiceUnavailable,
            )
        }
        call.respondHtmlPage(renderAuthorizePage(client.clientName, authorizeHiddenParams(p)))
    }

    /**
     * Handle the login + consent submission: verify the user's Commercials
     * Manager credentials and mint a single-use authorization code. Every
     * OAuth param arrives as a hidden field and is RE-validated from scratch
     * (hidden fields are still client input). Authorization responses carry
     * the RFC 9207 `iss` parameter.
     *
     * Tag: MCP
     */
    post("/oauth/authorize") {
        val p = runCatching { call.receiveParameters() }.getOrNull()
            ?: return@post call.respondHtmlPage(
                renderOAuthErrorPage("Invalid request", "The authorization form was malformed."),
                HttpStatusCode.BadRequest,
            )
        val client = call.validateClientAndRedirect(oauthDb, p) ?: return@post
        val redirectUri = p["redirect_uri"]!!
        val state = p["state"]

        val failure = validateAuthorizeParams(p, acceptedResources)
        if (failure != null) {
            return@post call.authorizeErrorRedirect(redirectUri, failure.first, failure.second, state, issuer)
        }
        if (!withContext(Dispatchers.IO) { authDb.isMcpEnabled() }) {
            return@post call.respondHtmlPage(
                renderOAuthErrorPage("MCP access disabled", "MCP/API access is currently disabled on this server. Contact your administrator."),
                HttpStatusCode.ServiceUnavailable,
            )
        }

        fun rerender(error: String) = renderAuthorizePage(client.clientName, authorizeHiddenParams(p), error)

        val username = p["username"].orEmpty()
        val password = p["password"].orEmpty()
        val user = withContext(Dispatchers.IO) { authDb.verifyCredentials(username, password) }
            ?: return@post call.respondHtmlPage(rerender("Invalid username or password."))
        if (user.mustChangePassword) {
            // A temporary password must never mint a 90-day grant.
            return@post call.respondHtmlPage(
                rerender("Your password must be changed in the Commercials Manager app before connecting an AI client.")
            )
        }

        val code = withContext(Dispatchers.IO) {
            oauthDb.createCode(
                clientId = client.clientId,
                userId = user.id,
                redirectUri = redirectUri,
                codeChallenge = p["code_challenge"]!!,
                codeChallengeMethod = p["code_challenge_method"] ?: "S256",
                resource = p["resource"],
                scope = p["scope"],
            )
        }
        val location = URLBuilder(redirectUri).apply {
            parameters.append("code", code)
            state?.let { parameters.append("state", it) }
            parameters.append("iss", issuer)
        }.buildString()
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondRedirect(location)
    }

    // ───────────────────────────────────────────────────────── token ──

    /**
     * Exchange an authorization code (PKCE-verified, single-use) or rotate a
     * refresh token. Form-urlencoded per RFC 6749 - claude.ai/ChatGPT send
     * forms here while /oauth/register gets JSON; a shared JSON-only parser is
     * a documented interop failure. Error codes follow RFC 6749 §5.2 exactly:
     * Claude re-runs the whole auth flow only on `invalid_grant`.
     *
     * Tag: MCP
     */
    post("/oauth/token") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.response.headers.append("Pragma", "no-cache")
        val p = runCatching { call.receiveParameters() }.getOrNull()
            ?: return@post call.respondOAuthError(
                HttpStatusCode.BadRequest, "invalid_request",
                "Body must be application/x-www-form-urlencoded"
            )

        val client = call.authenticateClient(oauthDb, p) ?: return@post

        when (p["grant_type"]) {
            "authorization_code" -> {
                val code = p["code"]
                val verifier = p["code_verifier"]
                val redirectUri = p["redirect_uri"]
                if (code.isNullOrBlank() || verifier.isNullOrBlank() || redirectUri.isNullOrBlank()) {
                    return@post call.respondOAuthError(
                        HttpStatusCode.BadRequest, "invalid_request",
                        "code, code_verifier and redirect_uri are required"
                    )
                }
                if (!isValidCodeVerifier(verifier)) {
                    return@post call.respondOAuthError(
                        HttpStatusCode.BadRequest, "invalid_request", "Malformed code_verifier"
                    )
                }
                val redeemed = withContext(Dispatchers.IO) { oauthDb.redeemCode(code) }
                if (redeemed == null ||
                    redeemed.clientId != client.clientId ||
                    redeemed.redirectUri != redirectUri ||
                    !challengeMatches(redeemed.codeChallenge, verifier)
                ) {
                    // One answer for expired/replayed/foreign/PKCE-failed codes.
                    return@post call.respondOAuthError(HttpStatusCode.BadRequest, "invalid_grant", "Invalid authorization code")
                }
                p["resource"]?.let { presented ->
                    val canonical = canonicalResource(presented)
                    val expected = redeemed.resource?.let(::canonicalResource)
                    val ok = if (expected != null) canonical == expected else canonical in acceptedResources
                    if (!ok) {
                        return@post call.respondOAuthError(
                            HttpStatusCode.BadRequest, "invalid_target", "Unknown resource"
                        )
                    }
                }
                val issued = withContext(Dispatchers.IO) {
                    oauthDb.issueTokens(redeemed.userId, client.clientId, redeemed.resource, redeemed.scope)
                }
                call.respondIssuedTokens(issued)
            }

            "refresh_token" -> {
                val refreshToken = p["refresh_token"]
                    ?: return@post call.respondOAuthError(
                        HttpStatusCode.BadRequest, "invalid_request", "refresh_token is required"
                    )
                val issued = withContext(Dispatchers.IO) {
                    oauthDb.rotateRefreshToken(refreshToken, client.clientId)
                } ?: return@post call.respondOAuthError(
                    // Exactly invalid_grant: a dead refresh token is how Claude learns to re-auth.
                    HttpStatusCode.BadRequest, "invalid_grant", "Invalid refresh token"
                )
                call.respondIssuedTokens(issued)
            }

            null -> call.respondOAuthError(HttpStatusCode.BadRequest, "invalid_request", "grant_type is required")
            else -> call.respondOAuthError(HttpStatusCode.BadRequest, "unsupported_grant_type", null)
        }
    }

    // ──────────────────────────────────────────────────────── revoke ──

    /**
     * Revoke an access or refresh token (RFC 7009). Always answers 200 for a
     * well-formed request, whether or not the token existed - the RFC forbids
     * leaking which. claude.ai calls this on connector disconnect.
     *
     * Tag: MCP
     */
    post("/oauth/revoke") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val p = runCatching { call.receiveParameters() }.getOrNull()
            ?: return@post call.respondOAuthError(
                HttpStatusCode.BadRequest, "invalid_request",
                "Body must be application/x-www-form-urlencoded"
            )
        val client = call.authenticateClient(oauthDb, p) ?: return@post
        val token = p["token"]
            ?: return@post call.respondOAuthError(HttpStatusCode.BadRequest, "invalid_request", "token is required")
        withContext(Dispatchers.IO) { oauthDb.revokeToken(token, client.clientId) }
        call.respond(HttpStatusCode.OK)
    }
}

// ─────────────────────────────────────────────────────────── helpers ──

private const val MAX_REDIRECT_URIS = 10
private const val MAX_REDIRECT_URI_LENGTH = 512
private val ALLOWED_TOKEN_AUTH_METHODS = setOf("none", "client_secret_post", "client_secret_basic")

/**
 * RFC 7591 registration request. Snake-case property names ARE the wire
 * names; unknown members (`application_type` arrives with the 2026-07-28
 * spec) pass through harmlessly via the root Json's ignoreUnknownKeys.
 */
@Serializable
@Suppress("PropertyName", "ConstructorParameterNaming")
private data class DcrRequest(
    val redirect_uris: List<String> = emptyList(),
    val client_name: String? = null,
    val token_endpoint_auth_method: String? = null,
)

/** The security headers every rendered OAuth page carries. */
private suspend fun ApplicationCall.respondHtmlPage(html: String, status: HttpStatusCode = HttpStatusCode.OK) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append("X-Frame-Options", "DENY")                             // clickjacking
    response.headers.append("Content-Security-Policy", "frame-ancestors 'none'")
    respondText(html, ContentType.Text.Html, status)
}

private suspend fun ApplicationCall.respondOAuthError(status: HttpStatusCode, error: String, description: String?) {
    respond(
        status,
        buildJsonObject {
            put("error", error)
            description?.let { put("error_description", it) }
        }
    )
}

private suspend fun ApplicationCall.respondDcrError(error: String, description: String) {
    respond(
        HttpStatusCode.BadRequest,
        buildJsonObject {
            put("error", error)
            put("error_description", description)
        }
    )
}

private suspend fun ApplicationCall.respondIssuedTokens(issued: OAuthDb.IssuedTokens) {
    respond(
        buildJsonObject {
            put("access_token", issued.accessToken)
            put("token_type", "Bearer")
            put("expires_in", issued.expiresInSeconds)   // Claude's proactive refresh needs it
            put("refresh_token", issued.refreshToken)
            issued.scope?.let { put("scope", it) }
        }
    )
}

/**
 * Client + redirect-URI validation shared by GET and POST /oauth/authorize.
 * Responds with an ERROR PAGE and returns null when the client is unknown or
 * the redirect URI is not registered - these two failures must NEVER redirect.
 */
private suspend fun ApplicationCall.validateClientAndRedirect(
    oauthDb: OAuthDb,
    p: Parameters,
): OAuthDb.OAuthClient? {
    val clientId = p["client_id"]
    if (clientId.isNullOrBlank()) {
        respondHtmlPage(
            renderOAuthErrorPage("Invalid request", "Missing client_id."),
            HttpStatusCode.BadRequest,
        )
        return null
    }
    val client = withContext(Dispatchers.IO) { oauthDb.findClient(clientId) }
    if (client == null) {
        respondHtmlPage(
            renderOAuthErrorPage("Unknown application", "This application is not registered with the server."),
            HttpStatusCode.BadRequest,
        )
        return null
    }
    val redirectUri = p["redirect_uri"]
    if (redirectUri.isNullOrBlank() || client.redirectUris.none { redirectUriMatches(it, redirectUri) }) {
        respondHtmlPage(
            renderOAuthErrorPage("Invalid redirect", "The redirect URI is not registered for this application."),
            HttpStatusCode.BadRequest,
        )
        return null
    }
    return client
}

/**
 * Post-client validation of the remaining authorize params. Returns
 * `error to description` for a REDIRECTABLE failure (the redirect URI is
 * already validated at this point), or null when everything holds.
 * PKCE downgrade defence lives here: no challenge, or any method but S256,
 * is refused at the front door.
 */
private fun validateAuthorizeParams(p: Parameters, acceptedResources: Set<String>): Pair<String, String>? {
    if (p["response_type"] != "code") {
        return "unsupported_response_type" to "Only response_type=code is supported"
    }
    if (p["code_challenge"].isNullOrBlank()) {
        return "invalid_request" to "code_challenge is required (PKCE)"
    }
    if ((p["code_challenge_method"] ?: "S256") != "S256") {
        return "invalid_request" to "Only code_challenge_method=S256 is supported"
    }
    p["resource"]?.let { presented ->
        if (canonicalResource(presented) !in acceptedResources) {
            return "invalid_target" to "Unknown resource"
        }
    }
    return null
}

/** The validated OAuth params, carried through the login form as hidden fields. */
private fun authorizeHiddenParams(p: Parameters): Map<String, String> =
    listOf("response_type", "client_id", "redirect_uri", "state", "code_challenge", "code_challenge_method", "scope", "resource")
        .mapNotNull { name -> p[name]?.let { name to it } }
        .toMap()

/** RFC 6749 §4.1.2.1 error redirect, with `state` echoed and RFC 9207 `iss` attached. */
private suspend fun ApplicationCall.authorizeErrorRedirect(
    redirectUri: String,
    error: String,
    description: String,
    state: String?,
    issuer: String,
) {
    val location = URLBuilder(redirectUri).apply {
        parameters.append("error", error)
        parameters.append("error_description", description)
        state?.let { parameters.append("state", it) }
        parameters.append("iss", issuer)
    }.buildString()
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondRedirect(location)
}

/**
 * Token/revoke-endpoint client authentication. Accepts `client_secret_basic`
 * (Authorization header) or `client_secret_post` (body); a client registered
 * with a secret method MUST present its secret, a `none` client may omit it
 * (but a presented one is still verified). Responds and returns null on
 * failure: 401 + `WWW-Authenticate: Basic` when header auth was attempted
 * (RFC 6749 §5.2), 401 otherwise.
 */
private suspend fun ApplicationCall.authenticateClient(oauthDb: OAuthDb, p: Parameters): OAuthDb.OAuthClient? {
    val basic = request.header(HttpHeaders.Authorization)
        ?.takeIf { it.startsWith("Basic ", ignoreCase = true) }
        ?.let { header ->
            runCatching {
                val decoded = String(Base64.getDecoder().decode(header.substring(6).trim()))
                val idx = decoded.indexOf(':')
                if (idx < 0) null else decoded.take(idx) to decoded.substring(idx + 1)
            }.getOrNull()
        }

    val clientId = basic?.first ?: p["client_id"]
    val secret = basic?.second ?: p["client_secret"]
    val usedHeader = basic != null

    suspend fun fail(): OAuthDb.OAuthClient? {
        if (usedHeader) response.headers.append(HttpHeaders.WWWAuthenticate, "Basic realm=\"oauth\"")
        respondOAuthError(HttpStatusCode.Unauthorized, "invalid_client", null)
        return null
    }

    if (clientId.isNullOrBlank()) return fail()
    val client = withContext(Dispatchers.IO) { oauthDb.findClient(clientId) } ?: return fail()

    val secretRequired = client.tokenEndpointAuthMethod != "none"
    if (secretRequired && secret.isNullOrBlank()) return fail()
    if (!secret.isNullOrBlank() && !oauthDb.clientSecretMatches(client, secret)) return fail()
    return client
}

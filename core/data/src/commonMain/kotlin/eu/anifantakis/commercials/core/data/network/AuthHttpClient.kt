package eu.anifantakis.commercials.core.data.network

import eu.anifantakis.commercials.core.data.session.AuthSession
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Thrown when the server rejects our bearer token (401). [onUnauthorized] has
 * already run (the session is cleared), so the UI's session-revision observer
 * bounces the user back to the login screen.
 */
class SessionExpiredException : Exception("Session expired - please sign in again")

/**
 * Ktor client for authenticated JSON calls. Two centralized behaviours every
 * authenticated caller needs:
 *
 * 1. Attaches the current bearer token per request (re-read each call via
 *    [tokenProvider], so it tracks login/logout without rebuilding the client).
 * 2. Handles 401 in ONE place: a rejected token means the session is dead
 *    (revoked, or the server was reset), so [onUnauthorized] clears it - which
 *    drives the app back to Login - and a typed [SessionExpiredException] is
 *    raised. `expectSuccess = true` ensures any non-2xx raises here instead of
 *    reaching `body()`, where a non-JSON error body would otherwise blow up as
 *    NoTransformationFoundException (the crash this replaces).
 *
 * [engine] is injectable so tests can drive it with a MockEngine; production
 * callers omit it and get the platform's default engine.
 */
fun authenticatedJsonClient(
    tokenProvider: () -> String?,
    onUnauthorized: () -> Unit,
    engine: HttpClientEngine? = null,
): HttpClient {
    val config: HttpClientConfig<*>.() -> Unit = {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        defaultRequest {
            tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        expectSuccess = true
        HttpResponseValidator {
            handleResponseExceptionWithRequest { cause, _ ->
                val status = (cause as? ResponseException)?.response?.status
                if (status == HttpStatusCode.Unauthorized) {
                    onUnauthorized()
                    throw SessionExpiredException()
                }
                // Any other failure propagates unchanged for the caller to handle.
            }
        }
    }
    return if (engine != null) HttpClient(engine, config) else HttpClient(config)
}

/** Convenience overload wired to an [AuthSession]. */
fun authenticatedJsonClient(session: AuthSession): HttpClient =
    authenticatedJsonClient(
        tokenProvider = { session.token },
        onUnauthorized = { session.clear() },
    )

package eu.anifantakis.commercials.core.data.network

import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * One abstract base owns ALL Ktor plumbing (kmp-developer data-layer
 * convention): JSON, timeouts, logging, base URL, and the typed verbs that
 * wrap every call in a [DataResult] via the proven [dataCall]/[remoteCall]
 * helpers. Each backend "personality" is a small subclass ([ApiHttpClient],
 * [PlainJsonHttpClient]) contributing only its extras - so every new
 * endpoint family keeps the exact same HTTP approach for free.
 *
 * - [baseUrlProvider] is read PER REQUEST (the defaultRequest block runs on
 *   every call): on web, AppConfig arrives asynchronously from /config, so
 *   the base URL must not be captured at construction time.
 * - [engine] is injectable for tests (MockEngine); production omits it and
 *   gets the platform default.
 */
abstract class CommonHttpClient(
    private val baseUrlProvider: () -> String,
    private val engine: HttpClientEngine? = null,
    private val logging: Boolean = true,
    private val requestTimeoutMillis: Long = 30_000L,
    private val connectTimeoutMillis: Long = 30_000L,
    private val socketTimeoutMillis: Long = 30_000L,
) {
    /** Per-request additions from the subclass (auth header, extra params). */
    protected open fun DefaultRequest.DefaultRequestBuilder.defaultRequestExtras() {}

    /** Plugin-level additions from the subclass (validators, expectSuccess). */
    protected open val additionalConfig: (HttpClientConfig<*>.() -> Unit)? = null

    val client: HttpClient by lazy {
        val config: HttpClientConfig<*>.() -> Unit = {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = this@CommonHttpClient.requestTimeoutMillis
                connectTimeoutMillis = this@CommonHttpClient.connectTimeoutMillis
                socketTimeoutMillis = this@CommonHttpClient.socketTimeoutMillis
            }
            if (logging) {
                install(Logging) {
                    logger = Logger.SIMPLE
                    level = LogLevel.INFO
                    sanitizeHeader { it == HttpHeaders.Authorization }
                }
            }
            defaultRequest {
                url.takeFrom(baseUrlProvider())
                defaultRequestExtras()
            }
            additionalConfig?.invoke(this)
        }
        if (engine != null) HttpClient(engine, config) else HttpClient(config)
    }

    // ── typed verbs: transport errors as DataError.Network ─────────────────

    suspend inline fun <reified Res> get(
        path: String,
        vararg query: Pair<String, Any?>,
    ): DataResult<Res, DataError.Network> = dataCall {
        client.get(path) { query.forEach { (k, v) -> if (v != null) parameter(k, v) } }.body<Res>()
    }

    suspend inline fun <reified Req : Any, reified Res> post(
        path: String,
        body: Req,
    ): DataResult<Res, DataError.Network> = dataCall {
        client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body<Res>()
    }

    suspend inline fun <reified Req : Any> putEmpty(
        path: String,
        body: Req,
    ): EmptyDataResult<DataError.Network> = dataCall {
        client.put(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        Unit
    }

    suspend fun deleteEmpty(path: String): EmptyDataResult<DataError.Network> = dataCall {
        client.delete(path)
        Unit
    }

    // ── remote verbs: server {"error"} text survives as RemoteError.Server ─

    suspend inline fun <reified Res> getRemote(
        path: String,
        vararg query: Pair<String, Any?>,
    ): DataResult<Res, RemoteError> = remoteCall {
        client.get(path) { query.forEach { (k, v) -> if (v != null) parameter(k, v) } }.body<Res>()
    }

    suspend inline fun <reified Req : Any, reified Res> postRemote(
        path: String,
        body: Req? = null,
    ): DataResult<Res, RemoteError> = remoteCall {
        client.post(path) {
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }.body<Res>()
    }

    suspend inline fun <reified Req : Any> postRemoteEmpty(
        path: String,
        body: Req,
    ): EmptyDataResult<RemoteError> = remoteCall {
        client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        Unit
    }

    suspend inline fun <reified Req : Any> putRemoteEmpty(
        path: String,
        body: Req,
    ): EmptyDataResult<RemoteError> = remoteCall {
        client.put(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        Unit
    }

    suspend fun deleteRemoteEmpty(path: String): EmptyDataResult<RemoteError> = remoteCall {
        client.delete(path)
        Unit
    }

    /** GET returning the raw body text (HTML previews and the like). */
    suspend fun getTextRemote(
        path: String,
        vararg query: Pair<String, Any?>,
    ): DataResult<String, RemoteError> = remoteCall {
        client.get(path) { query.forEach { (k, v) -> if (v != null) parameter(k, v) } }.bodyAsText()
    }
}

/**
 * The authenticated personality for the app's own server: bearer token from
 * the session per request (tracks login/logout without rebuilding), the
 * selected station stamped on every call (the server ignores it where
 * irrelevant), and the centralized 401 handling - a rejected token clears
 * the session (the UI's revision observer bounces to Login) and raises
 * [SessionExpiredException], which [dataCall]/[remoteCall] map to
 * UNAUTHORIZED. `expectSuccess = true`: non-2xx raises instead of reaching
 * `body()` on a non-JSON error payload.
 */
class ApiHttpClient(
    private val tokenProvider: () -> String?,
    private val onUnauthorized: () -> Unit,
    private val stationProvider: () -> String? = { null },
    engine: HttpClientEngine? = null,
    logging: Boolean = true,
    baseUrlProvider: () -> String = { eu.anifantakis.commercials.core.data.config.AppConfig.require().serverBaseUrl },
) : CommonHttpClient(baseUrlProvider = baseUrlProvider, engine = engine, logging = logging) {

    /** Production wiring: token/station/revocation all come from the session. */
    constructor(session: AuthSession, engine: HttpClientEngine? = null) : this(
        tokenProvider = { session.token },
        onUnauthorized = { session.clear() },
        stationProvider = { session.selectedStation?.id },
        engine = engine,
    )

    override fun DefaultRequest.DefaultRequestBuilder.defaultRequestExtras() {
        tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        if (url.parameters["station"] == null) {
            stationProvider()?.let { url.parameters.append("station", it) }
        }
    }

    override val additionalConfig: (HttpClientConfig<*>.() -> Unit) = {
        expectSuccess = true
        HttpResponseValidator {
            handleResponseExceptionWithRequest { cause, _ ->
                val status = (cause as? ResponseException)?.response?.status
                if (status == HttpStatusCode.Unauthorized) {
                    onUnauthorized()
                    throw SessionExpiredException()
                }
            }
        }
    }
}

/**
 * The unauthenticated personality (login, password recovery): same JSON,
 * timeouts, logging and base URL as everything else, but no bearer, no
 * station, and non-2xx statuses are returned - the auth flow inspects them
 * itself (401 on /login means wrong credentials, not an expired session).
 */
class PlainJsonHttpClient(
    engine: HttpClientEngine? = null,
    logging: Boolean = true,
    baseUrlProvider: () -> String = { eu.anifantakis.commercials.core.data.config.AppConfig.require().serverBaseUrl },
) : CommonHttpClient(baseUrlProvider = baseUrlProvider, engine = engine, logging = logging)

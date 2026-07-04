package eu.anifantakis.commercials.feature.auth.data

import eu.anifantakis.commercials.core.data.config.AppConfig
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.data.session.StationAccess
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.feature.auth.data.dto.ChangePasswordDto
import eu.anifantakis.commercials.feature.auth.data.dto.LoginRequestDto
import eu.anifantakis.commercials.feature.auth.data.dto.LoginResponseDto
import eu.anifantakis.commercials.feature.auth.data.dto.RecoverPasswordDto
import eu.anifantakis.commercials.feature.auth.data.dto.RecoveryCodesDto
import eu.anifantakis.commercials.feature.auth.domain.AuthError
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Talks to the server's /api/auth endpoints and OWNS the session lifecycle:
 * login stores the encrypted [AuthSession], logout/changePassword clear it.
 * Login and recovery are unauthenticated; the rest attach the bearer token
 * explicitly (this client exists before/outside the authenticated one).
 */
class AuthRepositoryImpl(private val session: AuthSession) : AuthRepository {

    private val httpClient by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    private fun url(path: String) = "${AppConfig.require().serverBaseUrl}/api/auth/$path"

    override suspend fun login(username: String, password: String): EmptyDataResult<AuthError> = authCall {
        val response = httpClient.post(url("login")) {
            contentType(ContentType.Application.Json)
            setBody(LoginRequestDto(username.trim(), password))
        }
        when {
            response.status.isSuccess() -> {
                val dto: LoginResponseDto = response.body()
                // The super admin may log in with zero hosted stations (user
                // management works regardless); everyone else needs at least
                // one grant to have anything to see.
                if (dto.stations.isEmpty() && !dto.isAdmin) {
                    DataResult.Failure(AuthError.NoStationsAssigned)
                } else {
                    session.store(
                        token = dto.token,
                        displayName = dto.displayName,
                        isAdmin = dto.isAdmin,
                        stations = dto.stations.map {
                            StationAccess(it.id, it.name, it.role, it.clientCode)
                        },
                    )
                    DataResult.Success(Unit)
                }
            }
            response.status == HttpStatusCode.Unauthorized ->
                DataResult.Failure(AuthError.InvalidCredentials)
            else -> DataResult.Failure(AuthError.Server(response.errorMessage()))
        }
    }

    override suspend fun logout() {
        val token = session.token
        if (token != null) {
            try {
                httpClient.post(url("logout")) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Server unreachable - still log out locally
            }
        }
        session.clear()
    }

    override suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ): EmptyDataResult<AuthError> = authCall {
        val token = session.token ?: return@authCall DataResult.Failure(AuthError.NotLoggedIn)
        val response = httpClient.post(url("password")) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordDto(currentPassword, newPassword))
        }
        if (response.status.isSuccess()) {
            session.clear()
            DataResult.Success(Unit)
        } else {
            DataResult.Failure(AuthError.Server(response.errorMessage()))
        }
    }

    override suspend fun recoverPassword(
        username: String,
        recoveryCode: String,
        newPassword: String,
    ): EmptyDataResult<AuthError> = authCall {
        val response = httpClient.post(url("recover")) {
            contentType(ContentType.Application.Json)
            setBody(RecoverPasswordDto(username.trim(), recoveryCode.trim(), newPassword))
        }
        if (response.status.isSuccess()) DataResult.Success(Unit)
        else DataResult.Failure(AuthError.Server(response.errorMessage()))
    }

    override suspend fun regenerateRecoveryCodes(): DataResult<List<String>, AuthError> = authCall {
        val token = session.token ?: return@authCall DataResult.Failure(AuthError.NotLoggedIn)
        val response = httpClient.post(url("recovery-codes")) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (response.status.isSuccess()) {
            val dto: RecoveryCodesDto = response.body()
            DataResult.Success(dto.codes)
        } else {
            DataResult.Failure(AuthError.Server(response.errorMessage()))
        }
    }

    /** Transport failures become [AuthError.Network]; cancellation rethrows. */
    private inline fun <T> authCall(block: () -> DataResult<T, AuthError>): DataResult<T, AuthError> =
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DataResult.Failure(AuthError.Network(DataError.Network.NO_INTERNET))
        }

    /** Best-effort extraction of the server's {"error": "..."} body. */
    private suspend fun HttpResponse.errorMessage(): String {
        val body = try {
            bodyAsText()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ""
        }
        val fromJson = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        return fromJson ?: "Server error $status"
    }
}

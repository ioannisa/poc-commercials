package eu.anifantakis.commercials.feature.auth.data.data_source

import eu.anifantakis.commercials.core.data.network.PlainJsonHttpClient
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.feature.auth.data.dto.ChangePasswordDto
import eu.anifantakis.commercials.feature.auth.data.dto.LoginRequestDto
import eu.anifantakis.commercials.feature.auth.data.dto.LoginResponseDto
import eu.anifantakis.commercials.feature.auth.data.dto.RecoverPasswordDto
import eu.anifantakis.commercials.feature.auth.data.dto.RecoveryCodesDto
import eu.anifantakis.commercials.feature.auth.domain.AuthError
import eu.anifantakis.commercials.feature.auth.domain.data_source.RemoteAuthDataSource
import eu.anifantakis.commercials.feature.auth.domain.model.GrantedStation
import eu.anifantakis.commercials.feature.auth.domain.model.LoginResult
import io.ktor.client.call.body
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
import kotlinx.coroutines.CancellationException

/**
 * Login and recovery are unauthenticated; the rest attach the bearer token
 * explicitly (this client exists before/outside the authenticated one).
 */
class RemoteAuthDataSourceImpl(http: PlainJsonHttpClient) : RemoteAuthDataSource {

    private val httpClient = http.client

    private fun url(path: String) = "/api/auth/$path"

    override suspend fun login(username: String, password: String): DataResult<LoginResult, AuthError> = authCall {
        val response = httpClient.post(url("login")) {
            contentType(ContentType.Application.Json)
            setBody(LoginRequestDto(username.trim(), password))
        }
        when {
            response.status.isSuccess() -> {
                val dto: LoginResponseDto = response.body()
                DataResult.Success(
                    LoginResult(
                        token = dto.token,
                        displayName = dto.displayName,
                        isAdmin = dto.isAdmin,
                        stations = dto.stations.map {
                            GrantedStation(it.id, it.name, it.role, it.clientCode)
                        },
                    )
                )
            }
            response.status == HttpStatusCode.Unauthorized ->
                DataResult.Failure(AuthError.InvalidCredentials)
            else -> DataResult.Failure(AuthError.Server(response.errorMessage()))
        }
    }

    override suspend fun logout(token: String) {
        try {
            httpClient.post(url("logout")) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Server unreachable - the caller still logs out locally
        }
    }

    override suspend fun changePassword(
        token: String,
        currentPassword: String,
        newPassword: String,
    ): EmptyDataResult<AuthError> = authCall {
        val response = httpClient.post(url("password")) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordDto(currentPassword, newPassword))
        }
        if (response.status.isSuccess()) DataResult.Success(Unit)
        else DataResult.Failure(AuthError.Server(response.errorMessage()))
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

    override suspend fun regenerateRecoveryCodes(token: String): DataResult<List<String>, AuthError> = authCall {
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

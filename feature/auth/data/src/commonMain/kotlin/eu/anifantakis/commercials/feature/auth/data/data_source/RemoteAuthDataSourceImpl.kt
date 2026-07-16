package eu.anifantakis.commercials.feature.auth.data.data_source

import eu.anifantakis.commercials.core.data.config.AppConfig
import eu.anifantakis.commercials.core.data.network.PlainJsonHttpClient
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.feature.auth.data.dto.ApiTokenDto
import eu.anifantakis.commercials.feature.auth.data.dto.ChangePasswordDto
import eu.anifantakis.commercials.feature.auth.data.dto.CreateApiTokenRequestDto
import eu.anifantakis.commercials.feature.auth.data.dto.CreateApiTokenResponseDto
import eu.anifantakis.commercials.feature.auth.data.dto.ForgotPasswordDto
import eu.anifantakis.commercials.feature.auth.data.dto.LoginRequestDto
import eu.anifantakis.commercials.feature.auth.data.dto.LoginResponseDto
import eu.anifantakis.commercials.feature.auth.data.dto.ResetPasswordDto
import eu.anifantakis.commercials.feature.auth.data.dto.ResetResultDto
import eu.anifantakis.commercials.feature.auth.data.dto.WorkstationAvailabilityDto
import eu.anifantakis.commercials.feature.auth.domain.AuthError
import eu.anifantakis.commercials.feature.auth.domain.data_source.RemoteAuthDataSource
import eu.anifantakis.commercials.feature.auth.domain.model.ApiToken
import eu.anifantakis.commercials.feature.auth.domain.model.CreatedApiToken
import eu.anifantakis.commercials.feature.auth.domain.model.GrantedStation
import eu.anifantakis.commercials.feature.auth.domain.model.LoginResult
import eu.anifantakis.commercials.feature.auth.domain.model.ResetOutcome
import eu.anifantakis.commercials.feature.auth.domain.model.WorkstationAvailability
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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
                        swaggerEnabled = dto.swaggerEnabled,
                        mustChangePassword = dto.mustChangePassword,
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

    override suspend fun forgotPassword(username: String): EmptyDataResult<AuthError> = authCall {
        val response = httpClient.post(url("forgot")) {
            contentType(ContentType.Application.Json)
            setBody(ForgotPasswordDto(username.trim()))
        }
        // The server always answers the same (anti-enumeration); only a transport
        // failure is a real error, which authCall already turns into Network.
        if (response.status.isSuccess()) DataResult.Success(Unit)
        else DataResult.Failure(AuthError.Server(response.errorMessage()))
    }

    override suspend fun resetPassword(
        username: String,
        code: String,
        newPassword: String,
    ): DataResult<ResetOutcome, AuthError> = authCall {
        val response = httpClient.post(url("reset")) {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordDto(username.trim(), code.trim(), newPassword))
        }
        if (!response.status.isSuccess()) {
            DataResult.Failure(AuthError.Server(response.errorMessage()))
        } else {
            val dto: ResetResultDto = response.body()
            val outcome = when (dto.status) {
                "ok" -> ResetOutcome.Success
                "locked" -> ResetOutcome.Locked(dto.retryAfterSeconds ?: 0L)
                "expired" -> ResetOutcome.Expired
                else -> ResetOutcome.Invalid(dto.retryAfterSeconds)   // "invalid_code" and anything unexpected
            }
            DataResult.Success(outcome)
        }
    }

    override suspend fun listApiTokens(token: String): DataResult<List<ApiToken>, AuthError> = authCall {
        val response = httpClient.get(url("api-tokens")) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (response.status.isSuccess()) {
            val dtos: List<ApiTokenDto> = response.body()
            DataResult.Success(dtos.map { ApiToken(it.id, it.workstationName, it.createdAt, it.lastUsedAt) })
        } else {
            DataResult.Failure(AuthError.Server(response.errorMessage()))
        }
    }

    override suspend fun checkWorkstation(
        token: String,
        workstation: String,
    ): DataResult<WorkstationAvailability, AuthError> = authCall {
        val response = httpClient.get(url("api-tokens/availability")) {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("workstation", workstation.trim())
        }
        if (response.status.isSuccess()) {
            val dto: WorkstationAvailabilityDto = response.body()
            val status = runCatching { WorkstationAvailability.valueOf(dto.status) }
                .getOrDefault(WorkstationAvailability.FREE)
            DataResult.Success(status)
        } else {
            DataResult.Failure(AuthError.Server(response.errorMessage()))
        }
    }

    override suspend fun createApiToken(
        token: String,
        workstation: String,
        confirmTakeover: Boolean,
    ): DataResult<CreatedApiToken, AuthError> = authCall {
        val response = httpClient.post(url("api-tokens")) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CreateApiTokenRequestDto(workstation.trim(), confirmTakeover))
        }
        when {
            response.status.isSuccess() -> {
                val dto: CreateApiTokenResponseDto = response.body()
                // The MCP SSE endpoint the client should point at, built from the same
                // base URL this data source already talks to.
                val mcpUrl = AppConfig.require().serverBaseUrl.trimEnd('/') + "/mcp"
                DataResult.Success(CreatedApiToken(dto.token, mcpUrl))
            }
            // 409 = the workstation belongs to another user and takeover was not
            // confirmed; surface it as a conflict so the UI can ask to take over.
            response.status == HttpStatusCode.Conflict ->
                DataResult.Failure(AuthError.Conflict)
            else -> DataResult.Failure(AuthError.Server(response.errorMessage()))
        }
    }

    override suspend fun revokeApiToken(token: String, id: Long): EmptyDataResult<AuthError> = authCall {
        val response = httpClient.delete(url("api-tokens/$id")) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (response.status.isSuccess()) DataResult.Success(Unit)
        else DataResult.Failure(AuthError.Server(response.errorMessage()))
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

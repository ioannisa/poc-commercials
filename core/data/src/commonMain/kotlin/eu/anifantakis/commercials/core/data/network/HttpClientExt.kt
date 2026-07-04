package eu.anifantakis.commercials.core.data.network

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * Wraps a Ktor call into a [DataResult] (kmp-developer error-handling
 * convention): expected failures are returned as typed [DataError.Network]
 * values, never thrown; CancellationException always rethrows.
 */
suspend inline fun <reified T> safeCall(execute: () -> HttpResponse): DataResult<T, DataError.Network> {
    val response = try {
        execute()
    } catch (e: SerializationException) {
        return DataResult.Failure(DataError.Network.SERIALIZATION)
    } catch (e: IOException) {
        return DataResult.Failure(DataError.Network.NO_INTERNET)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        return DataResult.Failure(DataError.Network.UNKNOWN)
    }
    return responseToResult(response)
}

suspend inline fun <reified T> responseToResult(response: HttpResponse): DataResult<T, DataError.Network> {
    return when (response.status.value) {
        in 200..299 -> try {
            DataResult.Success(response.body<T>())
        } catch (e: SerializationException) {
            DataResult.Failure(DataError.Network.SERIALIZATION)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DataResult.Failure(DataError.Network.UNKNOWN)
        }
        else -> DataResult.Failure(statusToNetworkError(response.status.value))
    }
}

fun statusToNetworkError(code: Int): DataError.Network = when (code) {
    400 -> DataError.Network.BAD_REQUEST
    401 -> DataError.Network.UNAUTHORIZED
    403 -> DataError.Network.FORBIDDEN
    404 -> DataError.Network.NOT_FOUND
    405 -> DataError.Network.METHOD_NOT_ALLOWED
    406 -> DataError.Network.NOT_ACCEPTABLE
    407 -> DataError.Network.PROXY_AUTHENTICATION_REQUIRED
    408 -> DataError.Network.REQUEST_TIMEOUT
    409 -> DataError.Network.CONFLICT
    410 -> DataError.Network.GONE
    413 -> DataError.Network.PAYLOAD_TOO_LARGE
    429 -> DataError.Network.TOO_MANY_REQUESTS
    in 400..499 -> DataError.Network.CLIENT_ERROR
    500 -> DataError.Network.INTERNAL_SERVER_ERROR
    501 -> DataError.Network.NOT_IMPLEMENTED
    502 -> DataError.Network.BAD_GATEWAY
    503 -> DataError.Network.SERVICE_UNAVAILABLE
    504 -> DataError.Network.GATEWAY_TIMEOUT
    else -> DataError.Network.SERVER_ERROR
}

/**
 * Like [dataCall], but server rejections keep their authoritative
 * {"error": ...} message as [RemoteError.Server].
 */
suspend inline fun <T> remoteCall(block: () -> T): DataResult<T, RemoteError> = try {
    DataResult.Success(block())
} catch (e: SessionExpiredException) {
    DataResult.Failure(RemoteError.Transport(DataError.Network.UNAUTHORIZED))
} catch (e: ResponseException) {
    DataResult.Failure(RemoteError.Server(e.response.serverErrorMessage()))
} catch (e: SerializationException) {
    DataResult.Failure(RemoteError.Transport(DataError.Network.SERIALIZATION))
} catch (e: IOException) {
    DataResult.Failure(RemoteError.Transport(DataError.Network.NO_INTERNET))
} catch (e: Exception) {
    if (e is CancellationException) throw e
    DataResult.Failure(RemoteError.Transport(DataError.Network.UNKNOWN))
}

/** Best-effort extraction of the server's {"error": "..."} body. */
suspend fun HttpResponse.serverErrorMessage(): String {
    val body = try {
        bodyAsText()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        ""
    }
    val fromJson = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
    return fromJson ?: "Server error $status"
}

/**
 * Wraps a call made through [authenticatedJsonClient] (expectSuccess=true:
 * non-2xx throws) into a [DataResult]. [SessionExpiredException] maps to
 * UNAUTHORIZED - by then the session is cleared and the UI is already
 * bouncing to Login.
 */
suspend inline fun <T> dataCall(block: () -> T): DataResult<T, DataError.Network> = try {
    DataResult.Success(block())
} catch (e: SessionExpiredException) {
    DataResult.Failure(DataError.Network.UNAUTHORIZED)
} catch (e: ResponseException) {
    DataResult.Failure(statusToNetworkError(e.response.status.value))
} catch (e: SerializationException) {
    DataResult.Failure(DataError.Network.SERIALIZATION)
} catch (e: IOException) {
    DataResult.Failure(DataError.Network.NO_INTERNET)
} catch (e: Exception) {
    if (e is CancellationException) throw e
    DataResult.Failure(DataError.Network.UNKNOWN)
}

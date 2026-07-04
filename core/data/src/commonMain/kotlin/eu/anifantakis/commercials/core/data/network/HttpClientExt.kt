package eu.anifantakis.commercials.core.data.network

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
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

        400 -> DataResult.Failure(DataError.Network.BAD_REQUEST)
        401 -> DataResult.Failure(DataError.Network.UNAUTHORIZED)
        403 -> DataResult.Failure(DataError.Network.FORBIDDEN)
        404 -> DataResult.Failure(DataError.Network.NOT_FOUND)
        405 -> DataResult.Failure(DataError.Network.METHOD_NOT_ALLOWED)
        406 -> DataResult.Failure(DataError.Network.NOT_ACCEPTABLE)
        407 -> DataResult.Failure(DataError.Network.PROXY_AUTHENTICATION_REQUIRED)
        408 -> DataResult.Failure(DataError.Network.REQUEST_TIMEOUT)
        409 -> DataResult.Failure(DataError.Network.CONFLICT)
        410 -> DataResult.Failure(DataError.Network.GONE)
        413 -> DataResult.Failure(DataError.Network.PAYLOAD_TOO_LARGE)
        429 -> DataResult.Failure(DataError.Network.TOO_MANY_REQUESTS)
        in 400..499 -> DataResult.Failure(DataError.Network.CLIENT_ERROR)

        500 -> DataResult.Failure(DataError.Network.INTERNAL_SERVER_ERROR)
        501 -> DataResult.Failure(DataError.Network.NOT_IMPLEMENTED)
        502 -> DataResult.Failure(DataError.Network.BAD_GATEWAY)
        503 -> DataResult.Failure(DataError.Network.SERVICE_UNAVAILABLE)
        504 -> DataResult.Failure(DataError.Network.GATEWAY_TIMEOUT)
        else -> DataResult.Failure(DataError.Network.SERVER_ERROR)
    }
}

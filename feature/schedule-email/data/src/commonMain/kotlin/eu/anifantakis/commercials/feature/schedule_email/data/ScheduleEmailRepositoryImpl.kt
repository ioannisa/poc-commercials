package eu.anifantakis.commercials.feature.schedule_email.data

import eu.anifantakis.commercials.core.data.config.AppConfig
import eu.anifantakis.commercials.core.data.network.SessionExpiredException
import eu.anifantakis.commercials.core.data.network.authenticatedJsonClient
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailActivityMonth
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailError
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailLogEntry
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailPreviewRequest
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailSpot
import eu.anifantakis.commercials.feature.schedule_email.domain.ScheduleEmailRepository
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

@Serializable
private data class ActivityDto(val year: Int, val month: Int, val placements: Int)

@Serializable
private data class SpotDto(val spotId: Long, val description: String, val placements: Int)

@Serializable
private data class LogDto(
    val id: Long,
    val customerCode: String,
    val customerName: String,
    val recipient: String,
    val subject: String,
    val year: Int,
    val month: Int,
    val spotCount: Int,
    val transmissionCount: Int,
    val sentBy: String,
    val sentAt: String,
    val status: String,
    val error: String? = null,
)

@Serializable
private data class SendRequestDto(
    val year: Int,
    val month: Int,
    val clientCode: String,
    val kind: String,
    val spotIds: List<Long> = emptyList(),
    val to: String? = null,
    val personalMessage: String? = null,
)

@Serializable
private data class SendResponseDto(val status: String = "", val to: String = "", val spots: String = "")

private fun ActivityDto.toDomain() = EmailActivityMonth(year, month, placements)
private fun SpotDto.toDomain() = EmailSpot(spotId, description, placements)
private fun LogDto.toDomain() = EmailLogEntry(
    id, customerCode, customerName, recipient, subject, year, month,
    spotCount, transmissionCount, sentBy, sentAt, status, error,
)

class ScheduleEmailRepositoryImpl(private val session: AuthSession) : ScheduleEmailRepository {

    private val httpClient by lazy { authenticatedJsonClient(session) }

    private fun base(path: String): String {
        val station = session.selectedStation?.id ?: ""
        return "${AppConfig.require().serverBaseUrl}/api/email/schedule/$path?station=$station"
    }

    override suspend fun activity(
        clientCode: String,
        kind: PartyKind,
    ): DataResult<List<EmailActivityMonth>, EmailError> = emailCall {
        httpClient.get(base("activity") + "&kind=${kind.wire}&clientCode=$clientCode")
            .body<List<ActivityDto>>().map { it.toDomain() }
    }

    override suspend fun spots(
        year: Int,
        month: Int,
        clientCode: String,
        kind: PartyKind,
    ): DataResult<List<EmailSpot>, EmailError> = emailCall {
        httpClient.get(base("spots") + "&year=$year&month=$month&kind=${kind.wire}&clientCode=$clientCode")
            .body<List<SpotDto>>().map { it.toDomain() }
    }

    override suspend fun history(
        limit: Int,
        clientCode: String?,
    ): DataResult<List<EmailLogEntry>, EmailError> = emailCall {
        val url = base("log") + "&limit=$limit" + (clientCode?.let { "&clientCode=$it" } ?: "")
        httpClient.get(url).body<List<LogDto>>().map { it.toDomain() }
    }

    override suspend fun previewHtml(request: EmailPreviewRequest): DataResult<String, EmailError> = emailCall {
        val url = base("preview") +
            "&year=${request.year}&month=${request.month}&kind=${request.kind.wire}" +
            "&clientCode=${request.clientCode}" +
            "&spotIds=${request.spotIds.joinToString(",")}" +
            (request.personalMessage?.takeIf { it.isNotBlank() }
                ?.let { "&personalMessage=${it.encodeURLParameter()}" } ?: "")
        httpClient.get(url).bodyAsText()
    }

    override suspend fun send(request: EmailPreviewRequest): DataResult<String, EmailError> = emailCall {
        val resp: SendResponseDto = httpClient.post(
            "${AppConfig.require().serverBaseUrl}/api/email/schedule/send" +
                "?station=${session.selectedStation?.id ?: ""}"
        ) {
            contentType(ContentType.Application.Json)
            setBody(
                SendRequestDto(
                    year = request.year,
                    month = request.month,
                    clientCode = request.clientCode,
                    kind = request.kind.wire,
                    spotIds = request.spotIds,
                    to = request.recipient.takeIf { it.isNotBlank() },
                    personalMessage = request.personalMessage?.takeIf { it.isNotBlank() },
                )
            )
        }.body()
        resp.status
    }

    /**
     * Server rejections keep their authoritative message ({"error": ...} ->
     * [EmailError.Server]); transport issues become [EmailError.Network].
     */
    private suspend inline fun <T> emailCall(block: () -> T): DataResult<T, EmailError> = try {
        DataResult.Success(block())
    } catch (e: SessionExpiredException) {
        DataResult.Failure(EmailError.Network(DataError.Network.UNAUTHORIZED))
    } catch (e: ResponseException) {
        DataResult.Failure(EmailError.Server(e.response.errorMessage()))
    } catch (e: SerializationException) {
        DataResult.Failure(EmailError.Network(DataError.Network.SERIALIZATION))
    } catch (e: IOException) {
        DataResult.Failure(EmailError.Network(DataError.Network.NO_INTERNET))
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        DataResult.Failure(EmailError.Network(DataError.Network.UNKNOWN))
    }
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

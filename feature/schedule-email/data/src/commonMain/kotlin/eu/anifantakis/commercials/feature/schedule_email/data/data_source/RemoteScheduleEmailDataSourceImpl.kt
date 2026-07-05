package eu.anifantakis.commercials.feature.schedule_email.data.data_source

import eu.anifantakis.commercials.core.data.network.ApiHttpClient
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.domain.util.map
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailActivityMonth
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailError
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailLogEntry
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailPreviewRequest
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailSpot
import eu.anifantakis.commercials.feature.schedule_email.domain.data_source.RemoteScheduleEmailDataSource
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

/** The generic remote failure, retold in this feature's vocabulary. */
private fun RemoteError.toEmailError(): EmailError = when (this) {
    is RemoteError.Server -> EmailError.Server(message)
    is RemoteError.Transport -> EmailError.Network(error)
}

private fun <T> DataResult<T, RemoteError>.asEmail(): DataResult<T, EmailError> = when (this) {
    is DataResult.Success -> this
    is DataResult.Failure -> DataResult.Failure(error.toEmailError())
}

class RemoteScheduleEmailDataSourceImpl(private val api: ApiHttpClient) : RemoteScheduleEmailDataSource {

    override suspend fun activity(
        clientCode: String,
        kind: PartyKind,
    ): DataResult<List<EmailActivityMonth>, EmailError> =
        api.getRemote<List<ActivityDto>>(
            "/api/email/schedule/activity",
            "kind" to kind.wire,
            "clientCode" to clientCode,
        ).asEmail().map { list -> list.map { it.toDomain() } }

    override suspend fun spots(
        year: Int,
        month: Int,
        clientCode: String,
        kind: PartyKind,
    ): DataResult<List<EmailSpot>, EmailError> =
        api.getRemote<List<SpotDto>>(
            "/api/email/schedule/spots",
            "year" to year, "month" to month, "kind" to kind.wire, "clientCode" to clientCode,
        ).asEmail().map { list -> list.map { it.toDomain() } }

    override suspend fun history(
        limit: Int,
        clientCode: String?,
    ): DataResult<List<EmailLogEntry>, EmailError> =
        api.getRemote<List<LogDto>>(
            "/api/email/schedule/log",
            "limit" to limit, "clientCode" to clientCode,
        ).asEmail().map { list -> list.map { it.toDomain() } }

    override suspend fun previewHtml(request: EmailPreviewRequest): DataResult<String, EmailError> =
        api.getTextRemote(
            "/api/email/schedule/preview",
            "year" to request.year,
            "month" to request.month,
            "kind" to request.kind.wire,
            "clientCode" to request.clientCode,
            "spotIds" to request.spotIds.joinToString(","),
            "personalMessage" to request.personalMessage?.takeIf { it.isNotBlank() },
        ).asEmail()

    override suspend fun send(request: EmailPreviewRequest): DataResult<String, EmailError> =
        api.postRemote<SendRequestDto, SendResponseDto>(
            "/api/email/schedule/send",
            SendRequestDto(
                year = request.year,
                month = request.month,
                clientCode = request.clientCode,
                kind = request.kind.wire,
                spotIds = request.spotIds,
                to = request.recipient.takeIf { it.isNotBlank() },
                personalMessage = request.personalMessage?.takeIf { it.isNotBlank() },
            ),
        ).asEmail().map { it.status }
}


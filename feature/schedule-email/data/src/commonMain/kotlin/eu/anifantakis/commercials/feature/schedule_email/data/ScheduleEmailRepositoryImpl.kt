package eu.anifantakis.commercials.feature.schedule_email.data

import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailActivityMonth
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailError
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailLogEntry
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailPreviewRequest
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailSpot
import eu.anifantakis.commercials.feature.schedule_email.domain.ScheduleEmailRepository
import eu.anifantakis.commercials.feature.schedule_email.domain.data_source.RemoteScheduleEmailDataSource

/** Organizer only: all I/O lives in [RemoteScheduleEmailDataSource]. */
class ScheduleEmailRepositoryImpl(
    private val remoteDataSource: RemoteScheduleEmailDataSource,
) : ScheduleEmailRepository {

    override suspend fun activity(
        clientCode: String,
        kind: PartyKind,
    ): DataResult<List<EmailActivityMonth>, EmailError> = remoteDataSource.activity(clientCode, kind)

    override suspend fun spots(
        year: Int,
        month: Int,
        clientCode: String,
        kind: PartyKind,
    ): DataResult<List<EmailSpot>, EmailError> = remoteDataSource.spots(year, month, clientCode, kind)

    override suspend fun history(
        limit: Int,
        clientCode: String?,
    ): DataResult<List<EmailLogEntry>, EmailError> = remoteDataSource.history(limit, clientCode)

    override suspend fun previewHtml(request: EmailPreviewRequest): DataResult<String, EmailError> =
        remoteDataSource.previewHtml(request)

    override suspend fun send(request: EmailPreviewRequest): DataResult<String, EmailError> =
        remoteDataSource.send(request)
}

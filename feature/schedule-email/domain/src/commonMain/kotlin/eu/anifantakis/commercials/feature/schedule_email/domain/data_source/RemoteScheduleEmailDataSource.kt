package eu.anifantakis.commercials.feature.schedule_email.domain.data_source

import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.feature.schedule_email.domain.*

/**
 * Network side of the feature - the only class that touches the HTTP
 * client. Same surface as the repository: the repository is the organizer,
 * this is the wire.
 */
interface RemoteScheduleEmailDataSource {

    suspend fun activity(clientCode: String, kind: PartyKind): DataResult<List<EmailActivityMonth>, EmailError>

    suspend fun spots(
        year: Int,
        month: Int,
        clientCode: String,
        kind: PartyKind,
    ): DataResult<List<EmailSpot>, EmailError>

    suspend fun history(limit: Int, clientCode: String?): DataResult<List<EmailLogEntry>, EmailError>

    /** The exact HTML the email would carry - shown in the preview webview. */
    suspend fun previewHtml(request: EmailPreviewRequest): DataResult<String, EmailError>

    /** Sends the email; returns the server's status line. */
    suspend fun send(request: EmailPreviewRequest): DataResult<String, EmailError>
}

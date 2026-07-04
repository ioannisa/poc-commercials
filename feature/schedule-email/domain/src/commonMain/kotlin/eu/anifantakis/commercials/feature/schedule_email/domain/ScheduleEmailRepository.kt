package eu.anifantakis.commercials.feature.schedule_email.domain

import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataResult

/**
 * The customer schedule-email workflow (the legacy ΠΡΟΓΡΑΜΜΑΤΙΣΜΟΙ): party
 * activity drill-down, the month's spots, the exact HTML preview, the send,
 * and the audit trail. The party SEARCH lives in core (PartySearchRepository).
 */
interface ScheduleEmailRepository {

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

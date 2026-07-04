package eu.anifantakis.commercials.feature.schedule_email.domain

import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.Error

/**
 * Schedule-email failures. [Server] carries the backend's own message (SMTP
 * misconfiguration, "no spots this month", ... - authoritative operator
 * text); transport problems ride as [Network].
 */
sealed interface EmailError : Error {
    data class Server(val message: String) : EmailError
    data class Network(val error: DataError.Network) : EmailError
}

/** One month in which the selected party has airings. */
data class EmailActivityMonth(val year: Int, val month: Int, val placements: Int)

/** One spot (creative) the party ran in the chosen month. */
data class EmailSpot(val spotId: Long, val description: String, val placements: Int)

/** One archived send (the anti-double-send audit trail). */
data class EmailLogEntry(
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

/**
 * Everything the preview/send step needs, assembled by the main dialog and
 * handed to the preview screen (what you see is what goes).
 */
data class EmailPreviewRequest(
    val year: Int,
    val month: Int,
    val clientCode: String,
    val kind: PartyKind,
    val spotIds: List<Long>,
    val recipient: String,
    val personalMessage: String?,
)

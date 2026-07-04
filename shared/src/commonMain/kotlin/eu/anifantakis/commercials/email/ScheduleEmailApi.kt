package eu.anifantakis.commercials.email

import eu.anifantakis.commercials.auth.AuthSession
import eu.anifantakis.commercials.auth.authenticatedJsonClient
import eu.anifantakis.commercials.config.AppConfig
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

@Serializable
data class EmailCustomer(
    val code: String,
    val name: String,
    val email: String? = null,
    val spotCount: Int = 0,
    val placementCount: Int = 0,
)

@Serializable
data class EmailSpot(val spotId: Long, val description: String, val placements: Int)

/** One month in which the selected party has airings. */
@Serializable
data class EmailActivityMonth(val year: Int, val month: Int, val placements: Int)

@Serializable
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

/** Whose schedule is being emailed - see [ScheduleEmailApi.search]. */
enum class PartyKind(val wire: String) {
    /** The spot's owner (legacy cusID). */
    CUSTOMER("customer"),

    /** The contract's payer (legacy traid) - an agency in "triangular" deals. */
    TRADER("trader"),
}

@Serializable
private data class SendRequest(
    val year: Int,
    val month: Int,
    val clientCode: String,
    val kind: String = "customer",
    val spotIds: List<Long> = emptyList(),
    val to: String? = null,
    val personalMessage: String? = null,
)

@Serializable
private data class SendResponse(val status: String = "", val to: String = "", val spots: String = "")

/**
 * Client for the customer schedule-email endpoints (staff action). Sends ONE
 * email per customer, with one section per spot - the operator picks the
 * customer, reviews the spots that will be included, and sends.
 *
 * Koin singleton; the current station comes from the session.
 */
class ScheduleEmailApi(private val session: AuthSession) {

    private val httpClient by lazy { authenticatedJsonClient(session) }

    private fun base(path: String): String {
        val station = session.selectedStation?.id ?: ""
        return "${AppConfig.require().serverBaseUrl}/api/email/schedule/$path?station=$station"
    }

    /**
     * Substring search over all parties with airings ([query] min 3 chars,
     * matched as `%query%` server-side). The caller debounces; the month is
     * picked afterwards from [activity].
     */
    suspend fun search(query: String, kind: PartyKind): Result<List<EmailCustomer>> = runCatching {
        httpClient.get(
            base("customers") + "&kind=${kind.wire}&q=${query.encodeURLParameter()}"
        ).body()
    }

    /** The party's active months, newest first - the year/month drill-down. */
    suspend fun activity(clientCode: String, kind: PartyKind): Result<List<EmailActivityMonth>> = runCatching {
        httpClient.get(base("activity") + "&kind=${kind.wire}&clientCode=$clientCode").body()
    }

    suspend fun spots(year: Int, month: Int, clientCode: String, kind: PartyKind): Result<List<EmailSpot>> = runCatching {
        httpClient.get(base("spots") + "&year=$year&month=$month&kind=${kind.wire}&clientCode=$clientCode").body()
    }

    /** The exact HTML the email would carry - shown in the preview webview. */
    suspend fun previewHtml(
        year: Int,
        month: Int,
        clientCode: String,
        kind: PartyKind,
        spotIds: List<Long>,
        personalMessage: String?,
    ): Result<String> = runCatching {
        val url = base("preview") +
            "&year=$year&month=$month&kind=${kind.wire}&clientCode=$clientCode" +
            "&spotIds=${spotIds.joinToString(",")}" +
            (personalMessage?.takeIf { it.isNotBlank() }
                ?.let { "&personalMessage=${it.encodeURLParameter()}" } ?: "")
        val resp = httpClient.get(url)
        if (!resp.status.isSuccess()) error("Preview failed: ${resp.status}")
        resp.bodyAsText()
    }

    /** Send audit trail, newest first; [clientCode] null = the whole station. */
    suspend fun history(limit: Int = 50, clientCode: String? = null): Result<List<EmailLogEntry>> = runCatching {
        val url = base("log") + "&limit=$limit" + (clientCode?.let { "&clientCode=$it" } ?: "")
        httpClient.get(url).body()
    }

    /** Sends the email. [spotIds] empty = all of the party's spots. Returns a status line. */
    suspend fun send(
        year: Int,
        month: Int,
        clientCode: String,
        kind: PartyKind,
        spotIds: List<Long>,
        to: String?,
        personalMessage: String?,
    ): Result<String> = runCatching {
        val resp: SendResponse = httpClient.post("${AppConfig.require().serverBaseUrl}/api/email/schedule/send?station=${session.selectedStation?.id ?: ""}") {
            contentType(ContentType.Application.Json)
            setBody(SendRequest(year, month, clientCode, kind.wire, spotIds, to?.takeIf { it.isNotBlank() }, personalMessage?.takeIf { it.isNotBlank() }))
        }.body()
        resp.status
    }
}

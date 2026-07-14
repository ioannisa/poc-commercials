package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.reports.dto.ReportRequest
import eu.anifantakis.commercials.server.auth.AuthUser
import eu.anifantakis.commercials.server.auth.StationGrant
import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.scheduler.CellRow
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.scheduler.StationDb
import eu.anifantakis.commercials.server.stations.SmtpConfig
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

// ── identity fixtures ───────────────────────────────────────────────────────

internal fun grant(
    stationId: String,
    role: UserRole = UserRole.NORMAL_USER,
    clientCode: String? = null,
) = StationGrant(stationId, role, clientCode)

internal fun caller(vararg grants: StationGrant, admin: Boolean = false) =
    McpCaller(AuthUser(id = 1, username = "user", displayName = "User", isAdmin = admin, grants = grants.toList()))

// ── port fakes (fakes, not mocks: they run anywhere and catch real bugs) ────

/**
 * In-memory station data. Only the reads/writes a test exercises need real values.
 *
 * There is no canned break catalog to hand it any more: a break IS a time, so
 * [byKey] - the month's cells - is the only place breaks come from.
 */
internal class FakeStationDataSource(
    private val byKey: Map<Pair<LocalTime, LocalDate>, List<CommercialRow>> = emptyMap(),
    private val customers: Map<String, StationDb.CustomerContact> = emptyMap(),
    private val parties: List<StationDb.PartyRow> = emptyList(),
) : StationDataSource {

    var addedPlacement: Triple<Long, LocalTime, LocalDate>? = null
    var deletedPlacement: Long? = null
    var reordered: List<Long>? = null
    var loggedEmails: Int = 0

    override fun customerByCode(code: String): StationDb.CustomerContact? = customers[code]
    override fun loadMonth(
        year: Int,
        month: Int,
    ): Pair<List<CellRow>, Map<Pair<LocalTime, LocalDate>, List<CommercialRow>>> = emptyList<CellRow>() to byKey

    override fun searchParties(query: String, byTrader: Boolean): List<StationDb.PartyRow> = parties
    override fun partyActivity(code: String, byTrader: Boolean): List<StationDb.ActivityMonth> = emptyList()
    override fun partyContractLines(code: String, byTrader: Boolean): List<StationDb.ContractLineRow> = emptyList()
    override fun contractLineSpots(lineId: Long): List<StationDb.LineSpotRow> = emptyList()
    override fun contractStatus(code: String, byTrader: Boolean): List<StationDb.ContractStatusRow> = emptyList()
    override fun placementStats(): StationDb.PlacementStats = StationDb.PlacementStats(0, null, null)

    override fun addPlacement(spotId: Long, time: LocalTime, date: LocalDate): CommercialRow? {
        addedPlacement = Triple(spotId, time, date)
        return null
    }

    override fun deletePlacement(placementId: Long): Boolean {
        deletedPlacement = placementId
        return true
    }

    override fun reorderPlacements(time: LocalTime, date: LocalDate, orderedIds: List<Long>): Boolean {
        reordered = orderedIds
        return true
    }

    override fun logEmail(entry: StationDb.EmailLogEntry): Long {
        loggedEmails++
        return loggedEmails.toLong()
    }
}

internal class FakeStationDirectory(
    private val sources: Map<String, StationDataSource> = emptyMap(),
    private val names: Map<String, String> = emptyMap(),
    private val smtps: Map<String, SmtpConfig> = emptyMap(),
    private val logos: Map<String, String> = emptyMap(),
) : StationDirectory {
    override val ids: List<String> get() = sources.keys.toList()
    override fun name(stationId: String): String? = names[stationId]
    override fun smtp(stationId: String): SmtpConfig? = smtps[stationId]
    override fun logo(stationId: String): String? = logos[stationId]
    override fun dataSource(stationId: String): StationDataSource? = sources[stationId]
}

internal class FakeReportRenderer(private val pdf: ByteArray = byteArrayOf(37, 80, 68, 70)) : ReportRenderer {
    var rendered: ReportRequest? = null
    override fun renderPdf(request: ReportRequest): ByteArray {
        rendered = request
        return pdf
    }
}

internal class FakeReportStore : ReportStore {
    var savedName: String? = null
    var savedBytes: Int = 0
    override fun save(fileName: String, bytes: ByteArray): File {
        savedName = fileName
        savedBytes = bytes.size
        return File("/tmp/$fileName")
    }
}

internal class FakeEmailSender : EmailSender {
    val sent = mutableListOf<Triple<String, String, String>>() // to, subject, html
    override fun send(smtp: SmtpConfig, to: String, subject: String, html: String) {
        sent += Triple(to, subject, html)
    }
}

/** Assembles [McpToolServices] entirely from fakes — no MySQL, no Jasper, no SMTP. */
internal fun services(
    directory: StationDirectory = FakeStationDirectory(),
    mutationsEnabled: Boolean = false,
    renderer: ReportRenderer = FakeReportRenderer(),
    store: ReportStore = FakeReportStore(),
    emailSender: EmailSender = FakeEmailSender(),
) = McpToolServices(directory, renderer, store, emailSender, mutationsEnabled)

// ── domain-row fixtures ─────────────────────────────────────────────────────

internal fun commercial(clientCode: String, message: String = "SPOT", spotId: Long = 1) = CommercialRow(
    id = spotId * 10,
    spotId = spotId,
    position = 0,
    clientCode = clientCode,
    clientName = "ΠΕΛΑΤΗΣ $clientCode",
    message = message,
    durationSeconds = 30,
    type = "Διαφημίσεις τηλεόρασης",
    contract = "C-1",
    flow = "ΡΟΗ",
)

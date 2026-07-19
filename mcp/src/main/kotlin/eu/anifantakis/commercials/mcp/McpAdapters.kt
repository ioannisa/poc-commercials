package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.mailer.SmtpMailer
import eu.anifantakis.commercials.reports.dto.ReportRequest
import eu.anifantakis.commercials.reports.engine.ReportEngine
import eu.anifantakis.commercials.scheduleemail.ScheduleEmailAssembler.toSettings
import eu.anifantakis.commercials.server.scheduler.AddPlacementResult
import eu.anifantakis.commercials.server.scheduler.CellRow
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.scheduler.StationDb
import eu.anifantakis.commercials.server.stations.SmtpConfig
import eu.anifantakis.commercials.server.stations.StationRegistry
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/*
 * The adapters behind the ports. Each owns exactly ONE kind of I/O: the station
 * database, the station registry, the report engine, the filesystem, SMTP.
 * These are the ONLY classes in :mcp that touch those concrete collaborators.
 */

/** Adapts one station's [StationDb] — our JDBC "DAO" — to [StationDataSource]. */
class StationDbDataSource(private val db: StationDb) : StationDataSource {

    override fun customerByCode(code: String): StationDb.CustomerContact? = db.customerByCode(code)
    override fun loadMonth(
        year: Int,
        month: Int,
    ): Pair<List<CellRow>, Map<Pair<LocalTime, LocalDate>, List<CommercialRow>>> = db.loadMonth(year, month)

    override fun searchParties(query: String, byTrader: Boolean): List<StationDb.PartyRow> =
        db.searchParties(query, byTrader)

    override fun partyActivity(code: String, byTrader: Boolean): List<StationDb.ActivityMonth> =
        db.partyActivity(code, byTrader)

    override fun partyContractLines(code: String, byTrader: Boolean): List<StationDb.ContractLineRow> =
        db.partyContractLines(code, byTrader)

    override fun contractLineSpots(lineId: Long): List<StationDb.LineSpotRow> = db.contractLineSpots(lineId)

    override fun contractStatus(code: String, byTrader: Boolean): List<StationDb.ContractStatusRow> =
        db.contractStatus(code, byTrader)

    override fun placementStats(): StationDb.PlacementStats = db.placementStats()

    override fun addPlacement(spotId: Long, time: LocalTime, date: LocalDate, programId: Long?): AddPlacementResult =
        db.addPlacement(spotId, time, date, programId)

    override fun deletePlacement(placementId: Long): Boolean = db.deletePlacement(placementId)

    override fun reorderPlacements(time: LocalTime, date: LocalDate, orderedIds: List<Long>): Boolean =
        db.reorderPlacements(time, date, orderedIds)

    override fun logEmail(entry: StationDb.EmailLogEntry): Long = db.logEmail(entry)
}

/** Adapts the [StationRegistry] (server.yaml + lazy per-station pools) to [StationDirectory]. */
class StationRegistryDirectory(private val registry: StationRegistry) : StationDirectory {
    override val ids: List<String> get() = registry.ids
    override fun name(stationId: String): String? = registry.config(stationId)?.name
    override fun groupName(stationId: String): String? = registry.group(stationId)?.name ?: registry.brandName
    override fun smtp(stationId: String): SmtpConfig? = registry.smtpFor(stationId)
    override fun logo(stationId: String): String? = registry.config(stationId)?.logo?.takeIf { it.isNotBlank() }
    override fun dataSource(stationId: String): StationDataSource? =
        registry.db(stationId)?.let(::StationDbDataSource)
    override val emailRedirectTo: String? get() = registry.emailRedirectTo
}

/** Renders through the shared JasperReports engine (headless, server-side). */
class JasperReportRenderer : ReportRenderer {
    override fun renderPdf(request: ReportRequest): ByteArray = ReportEngine.generatePdf(request)
}

/** Writes generated PDFs under [outputDir]. */
class FileReportStore(
    private val outputDir: File = File(System.getProperty("java.io.tmpdir"), "commercials-mcp-reports"),
) : ReportStore {
    override fun save(fileName: String, bytes: ByteArray): File {
        if (!outputDir.exists()) outputDir.mkdirs()
        val safeName = File(fileName).name.ifBlank { "report.pdf" }
        return File(outputDir, safeName).apply { writeBytes(bytes) }
    }
}

/** Sends via Jakarta Mail (the `mailer` module). */
class SmtpEmailSender : EmailSender {
    override fun send(smtp: SmtpConfig, to: String, subject: String, html: String) {
        SmtpMailer(smtp.toSettings()).sendHtml(to, subject, html)
    }
}

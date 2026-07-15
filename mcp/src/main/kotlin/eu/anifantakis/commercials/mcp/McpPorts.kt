package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.reports.dto.ReportRequest
import eu.anifantakis.commercials.scheduleemail.ScheduleEmailSource
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.scheduler.StationDb
import eu.anifantakis.commercials.server.stations.SmtpConfig
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/*
 * The ports the MCP tools depend on (DIP).
 *
 * `McpToolServices` is an ORGANIZER of these reads/writes, never an implementor:
 * it must not import the concrete JDBC `StationDb`, the JasperReports engine, the
 * filesystem, or an SMTP transport. Each adapter (McpAdapters.kt) owns exactly one
 * kind of I/O — which is what makes the tools fakeable without a live MySQL.
 */

/**
 * One station's data. Extends the schedule-email read port, so the shared
 * `ScheduleEmailAssembler.assemble` accepts a [StationDataSource] directly.
 */
interface StationDataSource : ScheduleEmailSource {
    // ── reads ────────────────────────────────────────────────────────────────
    fun searchParties(query: String, byTrader: Boolean): List<StationDb.PartyRow>
    fun partyActivity(code: String, byTrader: Boolean): List<StationDb.ActivityMonth>
    fun partyContractLines(code: String, byTrader: Boolean): List<StationDb.ContractLineRow>
    fun contractLineSpots(lineId: Long): List<StationDb.LineSpotRow>
    fun contractStatus(code: String, byTrader: Boolean): List<StationDb.ContractStatusRow>
    fun placementStats(): StationDb.PlacementStats

    // ── writes ───────────────────────────────────────────────────────────────
    fun addPlacement(spotId: Long, time: LocalTime, date: LocalDate): CommercialRow?
    fun deletePlacement(placementId: Long): Boolean
    fun reorderPlacements(time: LocalTime, date: LocalDate, orderedIds: List<Long>): Boolean
    fun logEmail(entry: StationDb.EmailLogEntry): Long
}

/** The hosted stations: their ids, display names, SMTP settings, and each one's data. */
interface StationDirectory {
    val ids: List<String>
    fun name(stationId: String): String?
    fun smtp(stationId: String): SmtpConfig?
    /** Report logo path from server.yaml; null when the station has none. */
    fun logo(stationId: String): String?
    /** Null when the station is not hosted. Opening its pool is lazy and blocking. */
    fun dataSource(stationId: String): StationDataSource?
    /**
     * TEST-ONLY: if set, every outgoing schedule email goes HERE instead of the
     * customer. Default null (normal delivery). It exists on this port so the MCP
     * send tool honours the same safety valve as the REST route - a test redirect
     * with a hole in it is worse than none.
     */
    val emailRedirectTo: String? get() = null
}

/** Renders a report request to PDF bytes. */
interface ReportRenderer {
    fun renderPdf(request: ReportRequest): ByteArray
}

/** Persists a generated report, returning the written file. */
interface ReportStore {
    fun save(fileName: String, bytes: ByteArray): File
}

/** Sends an already-rendered HTML email. */
interface EmailSender {
    fun send(smtp: SmtpConfig, to: String, subject: String, html: String)
}

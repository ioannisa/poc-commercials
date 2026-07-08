package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.reports.dto.ReportRequest
import eu.anifantakis.commercials.reports.engine.ReportEngine
import eu.anifantakis.commercials.server.auth.StationGrant
import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.scheduler.BreakSlotRow
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.scheduler.StationDb
import eu.anifantakis.commercials.server.stations.SmtpConfig
import eu.anifantakis.commercials.server.stations.StationRegistry
import java.io.File
import java.time.LocalDate

/**
 * A tool-level failure carrying a client-facing message. Thrown by tool bodies
 * to short-circuit with a clear reason; the [runTool] wrapper turns it into an
 * MCP tool error (`isError = true`) rather than crashing the session.
 */
class McpToolException(message: String) : Exception(message)

/** A station the caller may touch: its DB pool + the caller's grant (its role drives scoping). */
data class StationAccess(val db: StationDb, val grant: StationGrant)

/** One station visible to the caller (`list_stations` output). */
data class StationInfo(val id: String, val name: String, val role: String, val clientCode: String?)

/**
 * The backend surface the MCP tools call. Centralises station resolution and
 * authorization, mirroring the server's `Security.stationAccessOrRespond` and
 * role checks so the HTTP and stdio transports enforce the SAME rules.
 *
 * DB access is blocking JDBC - callers run these inside [runTool], which hops to
 * `Dispatchers.IO`.
 */
class McpToolServices(
    private val registry: StationRegistry,
    /** Where generated report PDFs are written (also returned to the caller). */
    private val reportOutputDir: File = File(System.getProperty("java.io.tmpdir"), "commercials-mcp-reports"),
    /**
     * Kill switch: when false, the mutation tools (add/delete/reorder placement,
     * send email) are not registered at all - a strictly read-only MCP server.
     */
    val mutationsEnabled: Boolean = false,
) {
    /**
     * Resolve a station the caller is entitled to, else throw a clear tool error
     * (mirrors the route helper's 400 missing / 403 no-grant / 404 unknown).
     */
    fun resolveStation(caller: McpCaller, stationId: String?): StationAccess {
        if (stationId.isNullOrBlank()) throw McpToolException("Parameter 'station' is required")
        val grant = caller.grantFor(stationId)
            ?: throw McpToolException("No access to station '$stationId'")
        // Pool creation + schema bootstrap are lazy and blocking on first touch.
        val db = registry.db(stationId)
            ?: throw McpToolException("Unknown station '$stationId'")
        return StationAccess(db, grant)
    }

    /** The stations this caller may see, each with the caller's role/clientCode on it. */
    fun stations(caller: McpCaller): List<StationInfo> =
        registry.ids.filter { caller.grantFor(it) != null }.map { id ->
            val grant = caller.grantFor(id)!!
            StationInfo(
                id = id,
                name = registry.config(id)?.name ?: id,
                role = grant.role.name,
                clientCode = grant.clientCode,
            )
        }

    /** True when the caller only sees their own client's data on this station. */
    fun isCustomerScoped(grant: StationGrant): Boolean = grant.role == UserRole.CUSTOMER_VIEWER

    /** For a customer-scoped caller, forbid querying another party's code. */
    fun requireCode(grant: StationGrant, code: String) {
        if (isCustomerScoped(grant) && grant.clientCode != code) {
            throw McpToolException(
                "Customer-scoped access: you may only query your own client code (${grant.clientCode})."
            )
        }
    }

    /**
     * The commercials of one break on one date, in air order - resolving the
     * break by its `HH:mm` label and applying customer scoping. Shared by
     * `spots_in_break` and `generate_break_report`. Blocking JDBC; call inside
     * [runTool]/[runToolBlocks].
     */
    /** Resolve a break by its `HH:mm` label, or throw a clear tool error. */
    fun resolveBreak(access: StationAccess, timeLabel: String): BreakSlotRow =
        access.db.loadBreaks().firstOrNull { it.label == timeLabel }
            ?: throw McpToolException(
                "No break labelled '$timeLabel'. Break labels are HH:mm on a 15-minute grid (e.g. 17:30)."
            )

    fun breakSpots(access: StationAccess, date: LocalDate, timeLabel: String): List<CommercialRow> {
        val slot = resolveBreak(access, timeLabel)
        val (_, byKey) = access.db.loadMonth(date.year, date.monthValue)
        val spots = byKey[slot.id to date].orEmpty()
        return if (isCustomerScoped(access.grant)) {
            spots.filter { it.clientCode == access.grant.clientCode }
        } else {
            spots
        }
    }

    /** Render a report request to PDF bytes via the shared JasperReports engine. */
    fun generatePdf(request: ReportRequest): ByteArray = ReportEngine.generatePdf(request)

    /** Persist a generated PDF under [reportOutputDir], returning the written file. */
    fun saveReport(fileName: String, bytes: ByteArray): File {
        if (!reportOutputDir.exists()) reportOutputDir.mkdirs()
        val safeName = File(fileName).name.ifBlank { "report.pdf" }
        return File(reportOutputDir, safeName).apply { writeBytes(bytes) }
    }

    // ── mutation guardrails ─────────────────────────────────────────────────

    /** Writes are staff work: require NORMAL_USER on the station (mirrors EmailRoutes). */
    fun requireStaff(grant: StationGrant) {
        if (grant.role != UserRole.NORMAL_USER) {
            throw McpToolException(
                "Requires full (NORMAL_USER) access on this station; your role is ${grant.role}."
            )
        }
    }

    /** The station's SMTP settings (its own override, else the file-wide default). */
    fun smtpFor(stationId: String): SmtpConfig? = registry.config(stationId)?.smtp ?: registry.defaultSmtp

    /** Display name for a station id. */
    fun stationName(stationId: String): String = registry.config(stationId)?.name ?: stationId

    /** Records a PERFORMED mutation (who + what) to the log - the write audit trail. */
    fun audit(caller: McpCaller, action: String, detail: String) {
        toolLogger.info("MCP mutation by '{}': {} {}", caller.user.username, action, detail)
    }
}

/**
 * Mutation kill switch: mutations are OFF unless `COMMERCIALS_MCP_MUTATIONS` is
 * truthy. Default-deny so a hosted, network-reachable MCP server is read-only
 * unless the operator explicitly opts in (e.g. a trusted stdio dev setup).
 */
fun mcpMutationsEnabled(): Boolean =
    System.getenv("COMMERCIALS_MCP_MUTATIONS")?.trim()?.lowercase() in setOf("1", "true", "yes", "on")

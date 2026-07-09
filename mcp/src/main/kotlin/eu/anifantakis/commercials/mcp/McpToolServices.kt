package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.reports.dto.ReportRequest
import eu.anifantakis.commercials.server.auth.StationGrant
import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.scheduler.BreakSlotRow
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.stations.SmtpConfig
import java.io.File
import java.time.LocalDate

/**
 * A tool-level failure carrying a client-facing message. Thrown by tool bodies
 * to short-circuit with a clear reason; the [runTool] wrapper turns it into an
 * MCP tool error (`isError = true`) rather than crashing the session.
 */
class McpToolException(message: String) : Exception(message)

/** A station the caller may touch: its data port + the caller's grant (its role drives scoping). */
data class StationAccess(val data: StationDataSource, val grant: StationGrant)

/** One station visible to the caller (`list_stations` output). */
data class StationInfo(val id: String, val name: String, val role: String, val clientCode: String?)

/**
 * A break in the airtime grid. Occupancy fields are null when no date was given
 * (pure grid discovery) and populated (customer-scoped) when a date is.
 */
data class BreakInfo(
    val label: String,
    val hour: Int,
    val minute: Int,
    val zone: String,
    val spotCount: Int?,
    val totalDurationSeconds: Int?,
    val programName: String?,
)

/**
 * The backend surface the MCP tools call. Centralises station resolution and
 * authorization, mirroring the server's `Security.stationAccessOrRespond` and
 * role checks so the HTTP and stdio transports enforce the SAME rules.
 *
 * It ORGANIZES the ports (McpPorts.kt) and performs no I/O of its own — no JDBC,
 * no report engine, no filesystem, no SMTP. That is what lets the tools be
 * unit-tested against fakes instead of a live MySQL.
 *
 * The port implementations are blocking; callers run these inside [runTool] /
 * [runToolBlocks], which hop to `Dispatchers.IO`.
 */
class McpToolServices(
    private val directory: StationDirectory,
    private val renderer: ReportRenderer,
    private val store: ReportStore,
    private val emailSender: EmailSender,
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
        val data = directory.dataSource(stationId)
            ?: throw McpToolException("Unknown station '$stationId'")
        return StationAccess(data, grant)
    }

    /** The stations this caller may see, each with the caller's role/clientCode on it. */
    fun stations(caller: McpCaller): List<StationInfo> =
        directory.ids.filter { caller.grantFor(it) != null }.map { id ->
            val grant = caller.grantFor(id)!!
            StationInfo(
                id = id,
                name = directory.name(id) ?: id,
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

    /** Resolve a break by its `HH:mm` label, or throw a clear tool error. */
    fun resolveBreak(access: StationAccess, timeLabel: String): BreakSlotRow =
        access.data.loadBreaks().firstOrNull { it.label == timeLabel }
            ?: throw McpToolException(
                "No break labelled '$timeLabel'. Break labels are HH:mm on a 15-minute grid (e.g. 17:30)."
            )

    /**
     * The commercials of one break on one date, in air order - resolving the break
     * by its `HH:mm` label and applying customer scoping. Shared by `spots_in_break`
     * and `generate_break_report`.
     */
    fun breakSpots(access: StationAccess, date: LocalDate, timeLabel: String): List<CommercialRow> {
        val slot = resolveBreak(access, timeLabel)
        val (_, byKey) = access.data.loadMonth(date.year, date.monthValue)
        val spots = byKey[slot.id to date].orEmpty()
        return if (isCustomerScoped(access.grant)) {
            spots.filter { it.clientCode == access.grant.clientCode }
        } else {
            spots
        }
    }

    /**
     * The station's breaks, ascending by air time — the discovery counterpart of
     * `spots_in_break`/`generate_break_report` (which need an exact label).
     *
     * - no [date]  -> just the grid (label + zone); occupancy fields null.
     * - with [date] -> each break carries that day's spot count / duration /
     *   programme, customer-scoped (a CUSTOMER_VIEWER sees only THEIR spots).
     * - [onlyWithSpots] keeps only occupied breaks (requires a date).
     * - [after] ("HH:mm") keeps only breaks later than that time; the FIRST
     *   result is the "next break".
     */
    fun listBreaks(
        access: StationAccess,
        date: LocalDate?,
        onlyWithSpots: Boolean,
        after: String?,
    ): List<BreakInfo> {
        if (onlyWithSpots && date == null) {
            throw McpToolException("onlyWithSpots needs a 'date' (occupancy is per-day).")
        }
        val afterMinutes = after?.let { parseHhMm(it) }
        val slots = access.data.loadBreaks()
            .sortedBy { it.hour * 60 + it.minute }
            .filter { afterMinutes == null || it.hour * 60 + it.minute > afterMinutes }

        if (date == null) {
            return slots.map { BreakInfo(it.label, it.hour, it.minute, it.zone.name, null, null, null) }
        }

        val (_, byKey) = access.data.loadMonth(date.year, date.monthValue)
        val scoped = isCustomerScoped(access.grant)
        return slots.mapNotNull { slot ->
            var spots = byKey[slot.id to date].orEmpty()
            if (scoped) spots = spots.filter { it.clientCode == access.grant.clientCode }
            if (onlyWithSpots && spots.isEmpty()) return@mapNotNull null
            BreakInfo(
                label = slot.label,
                hour = slot.hour,
                minute = slot.minute,
                zone = slot.zone.name,
                spotCount = spots.size,
                totalDurationSeconds = spots.sumOf { it.durationSeconds },
                programName = spots.firstOrNull()?.programName,
            )
        }
    }

    /** Render a report request to PDF bytes. */
    fun generatePdf(request: ReportRequest): ByteArray = renderer.renderPdf(request)

    /** Persist a generated PDF, returning the written file. */
    fun saveReport(fileName: String, bytes: ByteArray): File = store.save(fileName, bytes)

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
    fun smtpFor(stationId: String): SmtpConfig? = directory.smtp(stationId)

    /** Display name for a station id. */
    fun stationName(stationId: String): String = directory.name(stationId) ?: stationId

    /** Deliver an already-rendered schedule email. */
    fun sendEmail(smtp: SmtpConfig, to: String, subject: String, html: String) =
        emailSender.send(smtp, to, subject, html)

    /** Records a PERFORMED mutation (who + what) to the log - the write audit trail. */
    fun audit(caller: McpCaller, action: String, detail: String) {
        toolLogger.info("MCP mutation by '{}': {} {}", caller.user.username, action, detail)
    }

    /** Parse an `HH:mm` time-of-day to minutes-since-midnight, or throw a clear tool error. */
    private fun parseHhMm(value: String): Int {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(value.trim())
            ?: throw McpToolException("Invalid time '$value' - use HH:mm (e.g. 17:30).")
        val (h, min) = m.destructured
        val hh = h.toInt()
        val mm = min.toInt()
        if (hh !in 0..23 || mm !in 0..59) {
            throw McpToolException("Invalid time '$value' - hour 00-23, minute 00-59.")
        }
        return hh * 60 + mm
    }
}

/**
 * Mutation kill switch: mutations are OFF unless `COMMERCIALS_MCP_MUTATIONS` is
 * truthy. Default-deny so a hosted, network-reachable MCP server is read-only
 * unless the operator explicitly opts in (e.g. a trusted stdio dev setup).
 */
fun mcpMutationsEnabled(): Boolean =
    System.getenv("COMMERCIALS_MCP_MUTATIONS")?.trim()?.lowercase() in setOf("1", "true", "yes", "on")

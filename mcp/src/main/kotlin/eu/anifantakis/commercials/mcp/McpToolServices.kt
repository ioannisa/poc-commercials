package eu.anifantakis.commercials.mcp

import eu.anifantakis.commercials.reports.dto.ReportRequest
import eu.anifantakis.commercials.server.auth.StationGrant
import eu.anifantakis.commercials.server.auth.UserRole
import eu.anifantakis.commercials.server.scheduler.CommercialRow
import eu.anifantakis.commercials.server.stations.SmtpConfig
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

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

    /**
     * A break's `HH:mm` label, as a time. There is nothing to RESOLVE any more:
     * a break IS its time, so this parses and stops. It used to look the label up
     * in the station's break catalog and fail if it was absent - which also made
     * it impossible to air a spot at a time no spot had ever used, the very thing
     * the catalog was costing us.
     */
    fun parseBreakTime(timeLabel: String): LocalTime {
        val minutes = parseHhMm(timeLabel)
        return LocalTime.of(minutes / 60, minutes % 60)
    }

    /**
     * The commercials of one break on one date, in air order, with customer
     * scoping applied. Shared by `spots_in_break` and `generate_break_report`.
     * A time nothing aired at is not an error - it is an empty break.
     */
    fun breakSpots(access: StationAccess, date: LocalDate, timeLabel: String): List<CommercialRow> {
        val time = parseBreakTime(timeLabel)
        val (_, byKey) = access.data.loadMonth(date.year, date.monthValue)
        val spots = byKey[time to date].orEmpty()
        return if (isCustomerScoped(access.grant)) {
            spots.filter { it.clientCode == access.grant.clientCode }
        } else {
            spots
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

    /** TEST-ONLY: redirect every outgoing email here (blank = unset). */
    val emailRedirectTo: String? get() = directory.emailRedirectTo

    /** This station's report logo (server.yaml); null when it has none. */
    fun logoFor(stationId: String): String? = directory.logo(stationId)

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

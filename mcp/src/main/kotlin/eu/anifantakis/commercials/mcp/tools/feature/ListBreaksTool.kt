package eu.anifantakis.commercials.mcp.tools.feature

import eu.anifantakis.commercials.mcp.McpToolException
import eu.anifantakis.commercials.mcp.StationAccess
import eu.anifantakis.commercials.mcp.args
import eu.anifantakis.commercials.mcp.inputSchema
import eu.anifantakis.commercials.mcp.parseIsoDate
import eu.anifantakis.commercials.mcp.prop
import eu.anifantakis.commercials.mcp.runTool
import eu.anifantakis.commercials.mcp.tools.McpTool
import eu.anifantakis.commercials.mcp.tools.ToolContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * Break DISCOVERY — the counterpart of the label-based tools (spots_in_break,
 * generate_break_report), which need an exact `HH:mm`. Needed because migrated
 * stations carry off-grid air times (22:05, 21:01) that can't be guessed.
 */
object ListBreaksTool : McpTool {
    override val name = "list_breaks"
    override val description =
        "Discover a station's breaks (the airtime grid), ascending by time. Without a date " +
            "you get the grid (label + zone). With date='YYYY-MM-DD' each break also carries that day's " +
            "spot count, total duration and programme (customer accounts see only their own spots). Use " +
            "onlyWithSpots=true (needs a date) to list just the occupied breaks, and after='HH:mm' to find " +
            "the NEXT break after a time (the first result). The 'label' values feed spots_in_break / " +
            "generate_break_report."
    override val inputSchema = inputSchema(required = listOf("station")) {
        prop("station", "string", "Station id (see list_stations).")
        prop("date", "string", "Optional date YYYY-MM-DD; add it for per-day occupancy.")
        prop("onlyWithSpots", "boolean", "Only breaks that have spots that date (requires date). Default false.")
        prop("after", "string", "Only breaks later than this HH:mm; the first result is the next break.")
    }

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult = runTool(name) {
        val services = ctx.services
        val a = req.args
        val access = services.resolveStation(ctx.caller, a.stringOrNull("station"))
        val date = a.stringOrNull("date")?.let { parseIsoDate(it) }
        val breaks = listBreaks(
            access = access,
            customerScoped = services.isCustomerScoped(access.grant),
            date = date,
            onlyWithSpots = a.bool("onlyWithSpots", false),
            after = a.stringOrNull("after"),
        )
        buildJsonObject {
            date?.let { put("date", it.toString()) }
            put("breakCount", breaks.size)
            put("breaks", buildJsonArray {
                breaks.forEach { b ->
                    addJsonObject {
                        put("label", b.label)
                        put("zone", b.zone)
                        b.spotCount?.let { put("spotCount", it) }
                        b.totalDurationSeconds?.let { put("totalDurationSeconds", it) }
                        b.programName?.let { put("programName", it) }
                    }
                }
            })
        }
    }
}

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
 * The station's breaks, ascending by air time.
 * - no [date]  -> just the grid (label + zone); occupancy fields null.
 * - with [date] -> each break carries that day's spot count / duration / programme,
 *   customer-scoped when [customerScoped] (a CUSTOMER_VIEWER sees only THEIR spots).
 * - [onlyWithSpots] keeps only occupied breaks (requires a date).
 * - [after] ("HH:mm") keeps only breaks later than that time; the FIRST result is the "next break".
 */
internal fun listBreaks(
    access: StationAccess,
    customerScoped: Boolean,
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
    return slots.mapNotNull { slot ->
        var spots = byKey[slot.id to date].orEmpty()
        if (customerScoped) spots = spots.filter { it.clientCode == access.grant.clientCode }
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

package eu.anifantakis.commercials.mcp.tools.feature

import eu.anifantakis.commercials.mcp.McpToolException
import eu.anifantakis.commercials.mcp.StationAccess
import eu.anifantakis.commercials.mcp.args
import eu.anifantakis.commercials.mcp.inputSchema
import eu.anifantakis.commercials.mcp.parseIsoDate
import eu.anifantakis.commercials.mcp.prop
import eu.anifantakis.commercials.mcp.runTool
import eu.anifantakis.commercials.server.scheduler.BreakTimeRow
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
 * generate_break_report), which need an exact `HH:mm`. Needed because stations
 * air at off-grid times (22:05, 21:01) that cannot be guessed.
 *
 * `date` is REQUIRED, and that is the model showing through: a break is not part
 * of a station's standing configuration, it is a time something aired at. There
 * is no station-wide grid to ask for - only "what did this station break at, on
 * this day". (The tool used to answer date-less, off a stored catalog.)
 */
object ListBreaksTool : McpTool {
    override val name = "list_breaks"
    override val description =
        "Discover a station's breaks on a given day, ascending by time. A break exists where spots " +
            "aired, so date='YYYY-MM-DD' is required. Each break carries its label, zone, that day's " +
            "spot count, total duration and programme (customer accounts see only their own spots). Use " +
            "onlyWithSpots=true to drop breaks with nothing visible to you, and after='HH:mm' to find " +
            "the NEXT break after a time (the first result). The 'label' values feed spots_in_break / " +
            "generate_break_report."
    override val inputSchema = inputSchema(required = listOf("station", "date")) {
        prop("station", "string", "Station id (see list_stations).")
        prop("date", "string", "Date YYYY-MM-DD. Required - breaks exist per day, not per station.")
        prop("onlyWithSpots", "boolean", "Drop breaks with no spots visible to you. Default false.")
        prop("after", "string", "Only breaks later than this HH:mm; the first result is the next break.")
    }

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult = runTool(name) {
        val services = ctx.services
        val a = req.args
        val access = services.resolveStation(ctx.caller, a.stringOrNull("station"))
        val date = parseIsoDate(a.string("date"))
        val breaks = listBreaks(
            access = access,
            customerScoped = services.isCustomerScoped(access.grant),
            date = date,
            onlyWithSpots = a.bool("onlyWithSpots", false),
            after = a.stringOrNull("after"),
        )
        buildJsonObject {
            put("date", date.toString())
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

/** A break on a day: its time, and what aired in it (customer-scoped). */
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
 * The station's breaks on [date], ascending by air time - which is to say, the
 * DISTINCT times its airings landed on that day.
 * - customer-scoped when [customerScoped] (a CUSTOMER_VIEWER sees only THEIR spots).
 * - [onlyWithSpots] drops breaks with nothing visible to the caller. For staff every
 *   break has spots by construction; for a CUSTOMER_VIEWER it is the useful filter.
 * - [after] ("HH:mm") keeps only breaks later than that time; the FIRST result is the "next break".
 */
internal fun listBreaks(
    access: StationAccess,
    customerScoped: Boolean,
    date: LocalDate,
    onlyWithSpots: Boolean,
    after: String?,
): List<BreakInfo> {
    val afterMinutes = after?.let { parseHhMm(it) }

    // ONE query. The month grid's keys ARE the breaks - a break is the time an
    // airing landed on - so the day's breaks are read straight off them. (This
    // used to fetch the station's break catalog and index the month by its ids.)
    val (_, byKey) = access.data.loadMonth(date.year, date.monthValue)
    val times = byKey.keys.filter { it.second == date }
        .map { BreakTimeRow(it.first) }
        .distinct()
        .sortedBy { it.time }
        .filter { afterMinutes == null || it.hour * 60 + it.minute > afterMinutes }

    return times.mapNotNull { b ->
        var spots = byKey[b.time to date].orEmpty()
        if (customerScoped) spots = spots.filter { it.clientCode == access.grant.clientCode }
        if (onlyWithSpots && spots.isEmpty()) return@mapNotNull null
        BreakInfo(
            label = b.label,
            hour = b.hour,
            minute = b.minute,
            zone = b.zone.name,
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

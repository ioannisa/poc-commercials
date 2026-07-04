package eu.anifantakis.commercials.mailer

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Everything the schedule email needs - assembled by the caller (the server
 * builds it from the customer-filtered month grid). Colours are ARGB ints,
 * the same values the scheduler grid renders (programme identity colours).
 *
 * ONE email per customer, but with ONE SECTION PER SPOT (creative): each
 * spot the customer ran that month gets its own grid + summary, stacked in
 * order, so it is unambiguous which spot aired when.
 */
data class ScheduleEmailData(
    val stationName: String,
    /** Optional link target for the header (the station's site). */
    val stationSiteUrl: String? = null,
    val customerName: String,
    val year: Int,
    val month: Int,
    /** e.g. "Τηλεόραση" - shown next to the period. */
    val mediumLabel: String = "Τηλεόραση",
    /** The operator's free-text note shown above the sections (legacy tradition). */
    val personalMessage: String? = null,
    /** One section per spot (creative), in display order. */
    val spots: List<SpotSection>,
)

/** One spot's month grid + its per-programme breakdown. */
data class SpotSection(
    val description: String,
    val rows: List<EmailGridRow>,
    val programTotals: List<ProgramTotal>,
)

data class EmailGridRow(
    val label: String,
    /** Index 0 = day 1; null = no airing that day at this break. */
    val cells: List<EmailCell?>,
)

data class EmailCell(val count: Int, val colorArgb: Int?)

data class ProgramTotal(val name: String, val colorArgb: Int?, val spots: Int)

private const val NAVY = "#004080"          // legacy header blue, kept as homage
private const val WEEKEND_BG = "#FFF3D6"
private const val WEEKEND_TEXT = "#C62828"
private const val BORDER = "#D9D9D9"
private const val DEFAULT_CELL = "#EBF3FB"  // airings without a programme colour

private val GREEK_DAY = mapOf(
    DayOfWeek.MONDAY to "ΔΕ", DayOfWeek.TUESDAY to "ΤΡ", DayOfWeek.WEDNESDAY to "ΤΕ",
    DayOfWeek.THURSDAY to "ΠΕ", DayOfWeek.FRIDAY to "ΠΑ",
    DayOfWeek.SATURDAY to "ΣΑ", DayOfWeek.SUNDAY to "ΚΥ",
)

private val GREEK_MONTH = listOf(
    "Ιανουάριος", "Φεβρουάριος", "Μάρτιος", "Απρίλιος", "Μάιος", "Ιούνιος",
    "Ιούλιος", "Αύγουστος", "Σεπτέμβριος", "Οκτώβριος", "Νοέμβριος", "Δεκέμβριος",
)

/**
 * Renders the email body. Email-client HTML is deliberately old-school:
 * tables + inline styles only (no external CSS, no flexbox) - that is what
 * Outlook/Gmail render reliably, and exactly why the 2006 originals still
 * displayed fine twenty years later. Every element pins its own colours
 * (foreground AND background) so mail-client dark modes can't recolour it.
 */
fun renderScheduleEmail(data: ScheduleEmailData): String {
    val days = YearMonth.of(data.year, data.month).lengthOfMonth()
    val dates = (1..days).map { LocalDate.of(data.year, data.month, it) }
    val period = "${GREEK_MONTH[data.month - 1]} ${data.year}"

    val sb = StringBuilder()
    sb.append("<!DOCTYPE html><html lang=\"el\"><head><meta charset=\"utf-8\"></head>")
    sb.append("<body style=\"margin:0;padding:16px;background:#F4F5F7;font-family:Arial,Helvetica,sans-serif;\">")
    sb.append("<div style=\"max-width:980px;margin:0 auto;background:#FFFFFF;border:1px solid $BORDER;border-radius:8px;padding:24px;\">")

    // ── header: station identity + automated-mail marker ────────────────
    val title = esc(data.stationName)
    sb.append("<div style=\"border-bottom:2px solid $NAVY;padding-bottom:12px;margin-bottom:16px;\">")
    if (data.stationSiteUrl != null) {
        sb.append("<a href=\"${esc(data.stationSiteUrl)}\" style=\"text-decoration:none;color:$NAVY;\"><span style=\"font-size:22px;font-weight:bold;\">$title</span></a>")
    } else {
        sb.append("<span style=\"font-size:22px;font-weight:bold;color:$NAVY;\">$title</span>")
    }
    sb.append("<br><span style=\"font-size:11px;color:#777777;font-style:italic;\">Commercials Manager &mdash; Αυτοματοποιημένο email</span>")
    sb.append("</div>")

    sb.append("<h2 style=\"font-size:17px;margin:0 0 4px 0;color:#1A1C1E;\">ΠΕΛΑΤΗΣ: ${esc(data.customerName)}</h2>")
    sb.append("<h3 style=\"font-size:14px;margin:0 0 4px 0;color:#444444;font-weight:normal;\">Προγραμματισμοί &middot; $period &middot; ${esc(data.mediumLabel)}</h3>")
    val spotCount = data.spots.size
    val grandTotal = data.spots.sumOf { sec -> sec.rows.sumOf { r -> r.cells.sumOf { it?.count ?: 0 } } }
    sb.append("<div style=\"font-size:12px;margin:0 0 14px 0;color:#666666;\">$spotCount σποτ &middot; $grandTotal συνολικές μεταδόσεις</div>")

    data.personalMessage?.takeIf { it.isNotBlank() }?.let {
        sb.append("<div style=\"white-space:pre-wrap;background:#FFF9E6;border:1px solid #F0E1B0;border-radius:6px;padding:10px 12px;margin:0 0 18px 0;font-size:13px;color:#4A3F1F;\">${esc(it)}</div>")
    }

    // ── one section per spot ─────────────────────────────────────────────
    data.spots.forEachIndexed { index, section ->
        sb.append(renderSpotSection(index + 1, spotCount, section, days, dates))
    }

    sb.append("<div style=\"margin-top:20px;font-size:10px;color:#999999;\">${esc(data.stationName)} &middot; $period &middot; Commercials Manager</div>")
    sb.append("</div></body></html>")
    return sb.toString()
}

private fun renderSpotSection(
    ordinal: Int,
    total: Int,
    section: SpotSection,
    days: Int,
    dates: List<LocalDate>,
): String {
    val sb = StringBuilder()
    val sectionTotal = section.rows.sumOf { r -> r.cells.sumOf { it?.count ?: 0 } }

    // section heading: "ΣΠΟΤ 3/12 · <description> · N μεταδόσεις"
    sb.append("<div style=\"margin:${if (ordinal == 1) "0" else "26px"} 0 8px 0;\">")
    sb.append("<span style=\"display:inline-block;background:$NAVY;color:#FFFFFF;font-size:11px;font-weight:bold;padding:2px 8px;border-radius:3px;\">ΣΠΟΤ $ordinal/$total</span> ")
    sb.append("<span style=\"font-size:14px;font-weight:bold;color:#1A1C1E;\">${esc(section.description)}</span> ")
    sb.append("<span style=\"font-size:12px;color:#666666;\">&middot; $sectionTotal μεταδόσεις</span>")
    sb.append("</div>")

    sb.append("<table cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:11px;\">")

    // weekday row
    sb.append("<tr><td style=\"${headerCell()}\"></td>")
    for (d in dates) {
        val weekend = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
        val bg = if (weekend) WEEKEND_BG else "#FFFFFF"
        val fg = if (weekend) WEEKEND_TEXT else "#333333"
        sb.append("<td style=\"border:1px solid $BORDER;background:$bg;color:$fg;text-align:center;padding:3px 4px;font-weight:bold;\">${GREEK_DAY[d.dayOfWeek]}</td>")
    }
    sb.append("</tr>")

    // day-number row
    sb.append("<tr><td style=\"${headerCell()}\">Ώρα/Μέρα</td>")
    for (d in dates) sb.append("<td style=\"${headerCell()}text-align:center;\">${d.dayOfMonth}</td>")
    sb.append("</tr>")

    // break rows
    for (row in section.rows) {
        sb.append("<tr><td style=\"${headerCell()}white-space:nowrap;\">${esc(row.label)}</td>")
        for (i in 0 until days) {
            val cell = row.cells.getOrNull(i)
            if (cell == null || cell.count == 0) {
                sb.append("<td style=\"border:1px solid $BORDER;background:#FFFFFF;padding:3px 4px;\">&nbsp;</td>")
            } else {
                val bg = cell.colorArgb?.let(::argbToHex) ?: DEFAULT_CELL
                val fg = contrastText(cell.colorArgb)
                sb.append("<td style=\"border:1px solid $BORDER;background:$bg;color:$fg;text-align:center;padding:3px 4px;font-weight:bold;\">${cell.count}</td>")
            }
        }
        sb.append("</tr>")
    }

    // totals row
    val totals = (0 until days).map { i -> section.rows.sumOf { it.cells.getOrNull(i)?.count ?: 0 } }
    sb.append("<tr><td style=\"${headerCell()}\">Σύνολα</td>")
    for (t in totals) {
        sb.append("<td style=\"border:1px solid $BORDER;background:#EFEFEF;color:#1A1C1E;text-align:center;padding:3px 4px;font-weight:bold;\">${if (t > 0) t else "&nbsp;"}</td>")
    }
    sb.append("</tr></table>")

    // per-programme breakdown for THIS spot
    if (section.programTotals.isNotEmpty()) {
        sb.append("<table cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:12px;margin-top:6px;\">")
        for (p in section.programTotals.sortedByDescending { it.spots }) {
            val chip = p.colorArgb?.let(::argbToHex) ?: DEFAULT_CELL
            sb.append("<tr>")
            sb.append("<td style=\"border:1px solid $BORDER;background:$chip;width:22px;\">&nbsp;</td>")
            sb.append("<td style=\"border:1px solid $BORDER;background:#FFFFFF;color:#1A1C1E;padding:4px 10px;\">${esc(p.name)}</td>")
            sb.append("<td style=\"border:1px solid $BORDER;background:#FFFFFF;color:#1A1C1E;padding:4px 10px;text-align:right;font-weight:bold;\">${p.spots}</td>")
            sb.append("</tr>")
        }
        sb.append("</table>")
    }
    return sb.toString()
}

private fun headerCell() =
    "border:1px solid $BORDER;background:$NAVY;color:#FFFFFF;font-weight:bold;padding:3px 6px;"

private fun esc(s: String) = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun argbToHex(argb: Int): String =
    "#%02X%02X%02X".format((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

/** Black or white by the fill's luminance - programme colours can be dark. */
private fun contrastText(argb: Int?): String {
    if (argb == null) return "#000000"
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return if (luminance > 0.5) "#000000" else "#FFFFFF"
}

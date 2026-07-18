package eu.anifantakis.commercials.mailer

/**
 * The masthead that opens EVERY email. The hosting organisation's name
 * (server.yaml group `name`) sits on top - so a recipient always sees WHO the
 * mail is from - and, when the message concerns one outlet, the station name
 * appears beneath it. Everything is inline-styled with pinned colours: mail
 * clients ignore <style>, and dark modes must not recolour a masthead.
 *
 * The whole point is that these strings come from the LIVE server.yaml of the
 * installation, so the same build brands itself per deployment.
 */

internal const val BRAND_NAVY = "#004080"

/** Shared HTML escaper for branding text (org/station names come from config). */
internal fun escBrand(s: String): String = buildString(s.length) {
    for (ch in s) when (ch) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
        '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(ch)
    }
}

/**
 * The org (+ optional station) header block, ready to drop at the top of an
 * email card. [orgName] is the group's display name; [stationName] is set only
 * for a message about a specific station (e.g. a schedule email).
 */
fun emailMasthead(orgName: String, stationName: String? = null): String = buildString {
    append("<div style=\"border-bottom:2px solid $BRAND_NAVY;padding-bottom:10px;margin:0 0 18px;\">")
    append("<div style=\"font-size:18px;font-weight:bold;color:$BRAND_NAVY;\">${escBrand(orgName)}</div>")
    if (!stationName.isNullOrBlank()) {
        append("<div style=\"font-size:13px;color:#444444;margin-top:2px;\">${escBrand(stationName)}</div>")
    }
    append("</div>")
}

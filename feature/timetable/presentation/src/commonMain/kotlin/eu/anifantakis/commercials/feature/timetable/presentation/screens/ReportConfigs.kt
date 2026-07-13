package eu.anifantakis.commercials.feature.timetable.presentation.screens

import eu.anifantakis.commercials.reports.StationLogoCache
import eu.anifantakis.commercials.reports.models.ReportConfig

/**
 * The report settings for the CURRENTLY SELECTED station.
 *
 * Only the logo varies today, and it comes from the server's `server.yaml`
 * (`stations[].logo`) - a PATH, which only means something to the machine that
 * owns it. So who reads it depends on who RENDERS:
 *
 *  - **Desktop** renders in-process, on a machine that is usually not the
 *    server. It never sees that path: [StationLogoCache] fetches the logo's
 *    BYTES from the server and caches them to a local file, and that local path
 *    is what lands here.
 *  - **Everyone else** renders on the server, which stamps its own logo on the
 *    report and REFUSES any path a client sends (it would be a file read on the
 *    server host). Their cache returns null, and nothing is lost.
 *
 * Null means the station has no logo; the template prints its "LOGO" placeholder
 * rather than failing. A missing logo costs a logo, never a report.
 *
 * `suspend` because the desktop may have to go and get it. It is cached per
 * station per run, so this is one round trip, not one per report.
 */
internal suspend fun StationLogoCache.reportConfig(): ReportConfig =
    ReportConfig(logoPath = localLogoPath())

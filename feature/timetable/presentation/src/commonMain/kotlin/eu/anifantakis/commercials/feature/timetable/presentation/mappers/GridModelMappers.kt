package eu.anifantakis.commercials.feature.timetable.presentation.mappers

import androidx.compose.ui.graphics.Color
import eu.anifantakis.commercials.feature.timetable.domain.model.BreakSlotInfo
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import eu.anifantakis.commercials.feature.timetable.domain.model.ScheduleCell
import eu.anifantakis.commercials.grids.BreakSlot
import eu.anifantakis.commercials.grids.BreakZone
import eu.anifantakis.commercials.grids.CommercialItem
import eu.anifantakis.commercials.grids.DailyStats
import eu.anifantakis.commercials.grids.SchedulerCellData
import eu.anifantakis.commercials.grids.SchedulerKey
import eu.anifantakis.commercials.grids.StableDate
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.LocalTime

/*
 * Domain -> grids UI models. The grid engine (:grids) is Compose-bound, so
 * the domain layer never sees its types; presentation translates here.
 */

fun BreakSlotInfo.toUi(): BreakSlot = BreakSlot(
    id = id,
    time = LocalTime(hour, minute),
    label = label,
    zone = BreakZone.valueOf(zone),
    zoneColor = Color(zoneColorArgb.toLong() and 0xFFFFFFFFL),
)

fun PlacedCommercial.toUi(): CommercialItem = CommercialItem(
    id = id,
    clientCode = clientCode,
    clientName = clientName,
    message = message,
    durationSeconds = durationSeconds,
    type = type,
    contract = contract,
    flow = flow,
)

fun ScheduleCell.toUi(): Pair<SchedulerKey, SchedulerCellData> =
    SchedulerKey(breakId, date) to SchedulerCellData(
        spotCount = spotCount,
        totalDurationSeconds = totalDurationSeconds,
        zoneColor = Color(zoneColorArgb.toLong() and 0xFFFFFFFFL),
        commercials = commercials.map { it.toUi() }.toImmutableList(),
    )

/** Per-day totals for the grid footer (Σύνολα row). */
fun calculateDailyTotals(cells: Map<SchedulerKey, SchedulerCellData>): Map<StableDate, DailyStats> =
    cells.entries
        .groupBy { StableDate(it.key.date) }
        .mapValues { (_, entries) ->
            DailyStats(
                spotCount = entries.sumOf { it.value.spotCount },
                totalDurationSeconds = entries.sumOf { it.value.totalDurationSeconds },
            )
        }

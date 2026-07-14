package eu.anifantakis.commercials.data

import eu.anifantakis.commercials.grids.DailyStats
import eu.anifantakis.commercials.grids.SchedulerCellData
import eu.anifantakis.commercials.grids.SchedulerKey
import eu.anifantakis.commercials.grids.StableDate

object SampleData {

    fun calculateDailyTotals(
        cellData: Map<SchedulerKey, SchedulerCellData>
    ): Map<StableDate, DailyStats> {
        val totals = mutableMapOf<StableDate, DailyStats>()
        cellData.forEach { (key, data) ->
            val dateKey = StableDate(key.date)
            val current = totals[dateKey] ?: DailyStats(0, 0)
            totals[dateKey] = DailyStats(
                spotCount = current.spotCount + data.spotCount,
                totalDurationSeconds = current.totalDurationSeconds + data.totalDurationSeconds
            )
        }
        return totals
    }
}

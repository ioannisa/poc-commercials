package eu.anifantakis.poc.ctv.data

import eu.anifantakis.poc.ctv.grids.DailyStats
import eu.anifantakis.poc.ctv.grids.SchedulerCellData
import eu.anifantakis.poc.ctv.grids.SchedulerKey
import eu.anifantakis.poc.ctv.grids.StableDate

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

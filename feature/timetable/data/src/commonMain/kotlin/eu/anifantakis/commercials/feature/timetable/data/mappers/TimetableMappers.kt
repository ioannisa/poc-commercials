package eu.anifantakis.commercials.feature.timetable.data.mappers

import eu.anifantakis.commercials.feature.timetable.data.dto.BreakSlotDto
import eu.anifantakis.commercials.feature.timetable.data.dto.CellDto
import eu.anifantakis.commercials.feature.timetable.data.dto.CommercialDto
import eu.anifantakis.commercials.feature.timetable.data.dto.ContractLineDto
import eu.anifantakis.commercials.feature.timetable.data.dto.FinderSpotDto
import eu.anifantakis.commercials.feature.timetable.data.dto.ScheduleDto
import eu.anifantakis.commercials.feature.timetable.domain.model.BreakSlotInfo
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.domain.model.MonthSchedule
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import eu.anifantakis.commercials.feature.timetable.domain.model.ScheduleCell
import kotlinx.datetime.LocalDate

internal fun BreakSlotDto.toDomain(): BreakSlotInfo = BreakSlotInfo(
    id = id, hour = hour, minute = minute, label = label, zone = zone, zoneColorArgb = zoneColorArgb,
)

internal fun CommercialDto.toDomain(): PlacedCommercial = PlacedCommercial(
    id = id, position = position, clientCode = clientCode, clientName = clientName,
    message = message, durationSeconds = durationSeconds, type = type, contract = contract,
    excludeFromReports = excludeFromReports, flow = flow,
)

internal fun CellDto.toDomain(): ScheduleCell = ScheduleCell(
    breakId = breakId,
    date = LocalDate.parse(date),
    spotCount = spotCount,
    totalDurationSeconds = totalDurationSeconds,
    zoneColorArgb = zoneColorArgb,
    commercials = commercials.sortedBy { it.position }.map { it.toDomain() },
)

internal fun ScheduleDto.toDomain(): MonthSchedule = MonthSchedule(
    year = year, month = month, cells = cells.map { it.toDomain() },
)

internal fun ContractLineDto.toDomain(): ContractLine = ContractLine(
    lineId = lineId, contractNumber = contractNumber, isGift = isGift, lineNo = lineNo,
    desiredQty = desiredQty, spotCount = spotCount, placements = placements,
    totalSeconds = totalSeconds, entryDate = entryDate,
)

internal fun FinderSpotDto.toDomain(): ContractLineSpot = ContractLineSpot(
    spotId = spotId, description = description, durationSeconds = durationSeconds,
    placements = placements, totalSeconds = totalSeconds,
)

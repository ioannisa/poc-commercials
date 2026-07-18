package eu.anifantakis.commercials.feature.timetable.presentation.screens

import eu.anifantakis.commercials.core.domain.auth.AiChatProviderOption
import eu.anifantakis.commercials.core.domain.auth.AppRole
import eu.anifantakis.commercials.core.domain.auth.StationAccess
import eu.anifantakis.commercials.core.domain.auth.UserSession
import eu.anifantakis.commercials.reports.ReportPayload
import eu.anifantakis.commercials.reports.ReportService
import eu.anifantakis.commercials.reports.StationLogoCache
import eu.anifantakis.commercials.reports.models.ReportResult
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.EmptyDataResult
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.feature.timetable.domain.PlacementsRepository
import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.party_search.PartySearchRepository
import eu.anifantakis.commercials.feature.timetable.domain.FinderRepository
import eu.anifantakis.commercials.feature.timetable.domain.ScheduleRepository
import eu.anifantakis.commercials.feature.timetable.domain.TimetablePreferences
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLine
import eu.anifantakis.commercials.feature.timetable.domain.model.ContractLineSpot
import eu.anifantakis.commercials.feature.timetable.domain.model.BreakSlotInfo
import eu.anifantakis.commercials.feature.timetable.domain.model.GridViewMode
import eu.anifantakis.commercials.feature.timetable.domain.model.MonthSchedule
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import eu.anifantakis.commercials.feature.timetable.domain.model.ScheduleCell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.time.Instant
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * Base for timetable ViewModel tests: swaps the main dispatcher and starts a
 * minimal Koin so [eu.anifantakis.commercials.core.presentation.global_state.BaseGlobalViewModel]
 * can resolve its one Koin default (the [GlobalStateContainer]). The same
 * container instance is exposed so tests can observe the app-wide snackbar
 * effects (the "one error policy" assertion).
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class TimetableTestBase {
    protected val testDispatcher = UnconfinedTestDispatcher()
    protected lateinit var globalContainer: GlobalStateContainer

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        startKoin { modules(module { single { GlobalStateContainer() } }) }
        globalContainer = KoinPlatform.getKoin().get()
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }
}

// ── test data builders ────────────────────────────────────────────────────

val TEST_DATE: LocalDate = LocalDate(2026, 7, 1)

/**
 * A clock pinned to [TEST_DATE], for [TimetableViewModel].
 *
 * The grid opens on the month its clock says it is, and the month REPORTS walk the
 * days of that month looking for cells. With the real clock, a fixture pinned to
 * 2026-07-01 matched only while the machine's month happened to be July 2026 -
 * every month-report test would have gone red on the 1st of August, with nothing
 * in the codebase having changed. Pin the clock and the coupling is gone.
 */
val TEST_CLOCK: Clock = object : Clock {
    override fun now(): Instant =
        TEST_DATE.atStartOfDayIn(TimeZone.currentSystemDefault())
}

/** The break most fixtures air in. A break IS a time - there is no id to give it. */
val TEST_TIME: LocalTime = LocalTime(10, 0)

fun placed(
    id: Long,
    durationSeconds: Int = 30,
    position: Int = 0,
): PlacedCommercial = PlacedCommercial(
    id = id,
    position = position,
    clientCode = "CUS$id",
    clientName = "Client $id",
    message = "Spot $id",
    durationSeconds = durationSeconds,
    type = "TV",
    contract = "C$id",
    flow = "NORMAL",
)

/**
 * One CELL of the month grid, as `getMonth` now returns it: an AGGREGATE.
 *
 * [spots] only DERIVES the aggregate (the count and the duration) - the airings
 * themselves do not survive into the cell, because the month no longer ships
 * them (13,009 of them, 7.79 MB, to draw 1,295 boxes). Whoever needs them asks
 * for its own slice through [ScheduleRepository.getCommercials] - stock them on
 * [FakeScheduleRepository] and they arrive there, not here.
 */
fun cell(
    time: LocalTime = TEST_TIME,
    date: LocalDate = TEST_DATE,
    spots: List<PlacedCommercial> = emptyList(),
): ScheduleCell = ScheduleCell(
    time = time,
    date = date,
    spotCount = spots.size,
    totalDurationSeconds = spots.sumOf { it.durationSeconds },
    zoneColorArgb = 0xFFFFFFFF.toInt(),
    commercials = emptyList(),
)

/**
 * A month as the repository returns it: its ROWS and its CELLS, from ONE call.
 *
 * The rows default to the cells' own distinct times - which is exactly what the
 * server derives them from, so a fixture cannot accidentally describe a grid whose
 * rows and cells disagree. Pass [rows] to add empty scaffold rows on top.
 */
fun month(vararg cells: ScheduleCell, rows: List<BreakSlotInfo>? = null): MonthSchedule =
    MonthSchedule(
        year = 2026,
        month = 7,
        rows = rows ?: cells.map { BreakSlotInfo(it.time, "DEFAULT", 0) }.distinctBy { it.time },
        cells = cells.toList(),
    )

/** One ROW of the month's grid, as the repository returns it. */
fun breakSlot(hour: Int, minute: Int = 0): BreakSlotInfo = BreakSlotInfo(
    time = LocalTime(hour, minute),
    zone = "DEFAULT",
    zoneColorArgb = 0,
)

// ── fakes over the domain interfaces (mandatory in KMP) ──────────────────

/** The SLICE a caller asked the airings for - the whole point of the split. */
data class CommercialsQuery(
    val year: Int,
    val month: Int,
    /** One day (a day report), or null for the whole month. */
    val date: LocalDate?,
    /** One break (a console / a break report), or null for every break. */
    val time: LocalTime?,
)

class FakeScheduleRepository : ScheduleRepository {
    var monthResult: DataResult<MonthSchedule, DataError.Network> = DataResult.Success(month())

    /**
     * The MONTH's rows. Recorded in full, because both arguments are now
     * load-bearing: the rows a month has depend on what aired in it, and the
     * mode decides how much empty scaffold is drawn around them.
     */
    var breaksResult: DataResult<List<BreakSlotInfo>, DataError.Network> = DataResult.Success(emptyList())
    val breakLoads = mutableListOf<Triple<Int, Int, GridViewMode>>()
    val breaksFetches: Int get() = breakLoads.size

    /**
     * The airings the "server" holds, per cell - stock them with [stock]. They
     * reach a caller ONLY through [getCommercials]: the month grid does not carry
     * them any more.
     */
    val airings = mutableMapOf<Pair<LocalTime, LocalDate>, List<PlacedCommercial>>()

    /** Every slice that was actually FETCHED - what tests assert the console and the reports ask for. */
    val commercialLoads = mutableListOf<CommercialsQuery>()
    val commercialsFetches: Int get() = commercialLoads.size

    /** Set to exercise the failure branch of a fetch. */
    var commercialsFailure: DataError.Network? = null

    /** Puts [placements] in the (time, date) cell - the only place airings live. */
    fun stock(time: LocalTime, date: LocalDate, vararg placements: PlacedCommercial) {
        airings[time to date] = placements.toList()
    }

    override suspend fun getBreaks(
        year: Int,
        month: Int,
        mode: GridViewMode,
    ): DataResult<List<BreakSlotInfo>, DataError.Network> {
        breakLoads += Triple(year, month, mode)
        return breaksResult
    }

    /**
     * Aggregates only. Any airings a fixture put on a cell are DROPPED here, on
     * purpose and exactly as the real repository drops them: `/api/schedule`
     * returns a count, a duration and a colour per cell - never a spot.
     */
    override suspend fun getMonth(
        year: Int,
        month: Int,
        mode: GridViewMode,
    ): DataResult<MonthSchedule, DataError.Network> {
        monthLoads += mode
        return when (val result = monthResult) {
            is DataResult.Success -> DataResult.Success(
                result.data.copy(cells = result.data.cells.map { it.copy(commercials = emptyList()) })
            )
            is DataResult.Failure -> result
        }
    }

    /** Every mode the grid was loaded with - one entry per round trip. */
    val monthLoads = mutableListOf<GridViewMode>()

    /** The stocked airings, narrowed to the slice asked for (both filters optional). */
    override suspend fun getCommercials(
        year: Int,
        month: Int,
        date: LocalDate?,
        time: LocalTime?,
    ): DataResult<Map<Pair<LocalTime, LocalDate>, List<PlacedCommercial>>, DataError.Network> {
        commercialLoads += CommercialsQuery(year, month, date, time)
        commercialsFailure?.let { return DataResult.Failure(it) }
        return DataResult.Success(
            airings.filterKeys { (t, d) ->
                (date == null || d == date) && (time == null || t == time)
            }
        )
    }
}

class FakePlacementsRepository : PlacementsRepository {
    /** Next placement the server "creates" on add; override per test. */
    var nextAdded: PlacedCommercial = placed(id = 100)
    var addResult: DataResult<PlacedCommercial, DataError.Network>? = null
    var removeResult: EmptyDataResult<DataError.Network> = DataResult.Success(Unit)
    var reorderResult: EmptyDataResult<DataError.Network> = DataResult.Success(Unit)

    val removedIds = mutableListOf<Long>()
    val reorders = mutableListOf<Triple<LocalTime, LocalDate, List<Long>>>()

    override suspend fun add(
        spotId: Long,
        time: LocalTime,
        date: LocalDate,
    ): DataResult<PlacedCommercial, DataError.Network> = addResult ?: DataResult.Success(nextAdded)

    override suspend fun remove(placementId: Long): EmptyDataResult<DataError.Network> {
        removedIds += placementId
        return removeResult
    }

    override suspend fun reorder(
        time: LocalTime,
        date: LocalDate,
        orderedIds: List<Long>,
    ): EmptyDataResult<DataError.Network> {
        reorders += Triple(time, date, orderedIds)
        return reorderResult
    }
}

class FakeFinderRepository : FinderRepository {
    var lines: List<ContractLine> = emptyList()
    var spots: List<ContractLineSpot> = emptyList()
    override suspend fun contractLines(clientCode: String, kind: PartyKind) = DataResult.Success(lines)
    override suspend fun lineSpots(lineId: Long) = DataResult.Success(spots)
}

class FakePartySearchRepository : PartySearchRepository {
    var results: List<Party> = emptyList()
    override suspend fun search(query: String, kind: PartyKind) = DataResult.Success(results)
}

class FakeTimetablePreferences(override var showSpotTimes: Boolean = false) : TimetablePreferences

/**
 * Shared fake of the flow contract: records every verb call and lets tests
 * drive [commonState]. The whole point of the screens depending on the
 * interface rather than the concrete CommonViewModel.
 */
class FakeTimetableCommon : TimetableCommon {
    private val _commonState = MutableStateFlow(TimetableCommonState())
    override val commonState: StateFlow<TimetableCommonState> = _commonState.asStateFlow()

    fun emit(state: TimetableCommonState) { _commonState.value = state }

    var clears = 0
    /** ONE verb now loads the month's cells AND its rows - there is no loadBreaks. */
    val loads = mutableListOf<Pair<Int, Int>>()
    val viewModes = mutableListOf<GridViewMode>()
    /** The cells whose AIRINGS a screen asked for - the Break Console asks for its own, on open. */
    val commercialLoads = mutableListOf<Pair<LocalTime, LocalDate>>()
    val adds = mutableListOf<Triple<Long, LocalTime, LocalDate>>()
    val removes = mutableListOf<Pair<LocalTime, LocalDate>>()
    val reorders = mutableListOf<Triple<LocalTime, LocalDate, List<Long>>>()

    override fun clear() { clears++ }
    override fun loadMonth(year: Int, month: Int) { loads += (year to month) }
    override fun setViewMode(mode: GridViewMode) { viewModes += mode }
    override fun loadCommercials(time: LocalTime, date: LocalDate) { commercialLoads += (time to date) }
    override fun add(spotId: Long, time: LocalTime, date: LocalDate) { adds += Triple(spotId, time, date) }
    override fun removeLast(time: LocalTime, date: LocalDate) { removes += (time to date) }
    override fun reorder(time: LocalTime, date: LocalDate, orderedIds: List<Long>) { reorders += Triple(time, date, orderedIds) }
}

/**
 * A session with a fixed role. The ViewModel enforces `canEdit` and mirrors
 * the chrome's facts from here, so a fake is all the screens' tests need.
 * [selectStation] bumps [revision] exactly like the real AuthSession, which
 * is what drives the ViewModel's reload.
 */
class FakeUserSession(
    override val role: AppRole = AppRole.NORMAL_USER,
    override val displayName: String = "Tester",
    override val isAdmin: Boolean = false,
    override val swaggerEnabled: Boolean = false,
    override val aiChatProviders: List<AiChatProviderOption> = emptyList(),
    override val stations: List<StationAccess> = emptyList(),
    override val isLoggedIn: Boolean = true,
) : UserSession {
    private val _revision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = _revision.asStateFlow()

    var selectedStationId: String? = stations.firstOrNull()?.id
        private set

    override val selectedStation: StationAccess?
        get() = stations.firstOrNull { it.id == selectedStationId } ?: stations.firstOrNull()

    override fun selectStation(stationId: String) {
        if (stations.none { it.id == stationId }) return
        selectedStationId = stationId
        _revision.value++
    }

    /** Simulates a login/logout happening outside this screen. */
    fun bumpRevision() { _revision.value++ }
}

class FakeReportService(
    private val available: Boolean = true,
) : ReportService {
    val printed = mutableListOf<List<ReportPayload>>()
    val previewed = mutableListOf<List<ReportPayload>>()
    val exported = mutableListOf<String>()

    /** Override to exercise the Cancelled / Error outcome branches. */
    var result: ReportResult = ReportResult.Success("ok")

    override suspend fun exportToPdf(payloads: List<ReportPayload>, suggestedFileName: String): ReportResult {
        exported += suggestedFileName
        return result
    }

    override suspend fun preview(payloads: List<ReportPayload>): ReportResult {
        previewed += payloads
        return result
    }

    override suspend fun print(payloads: List<ReportPayload>): ReportResult {
        printed += payloads
        return result
    }

    override fun isReportGenerationAvailable(): Boolean = available
}

class FakeStationLogoCache(
    /** Override to exercise a report carrying a client-side logo. */
    private val path: String? = null,
) : StationLogoCache {
    override suspend fun localLogoPath(): String? = path
}

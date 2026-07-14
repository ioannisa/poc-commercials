package eu.anifantakis.commercials.feature.timetable.presentation.screens

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

fun cell(
    time: LocalTime = TEST_TIME,
    date: LocalDate = TEST_DATE,
    commercials: List<PlacedCommercial> = emptyList(),
): ScheduleCell = ScheduleCell(
    time = time,
    date = date,
    spotCount = commercials.size,
    totalDurationSeconds = commercials.sumOf { it.durationSeconds },
    zoneColorArgb = 0xFFFFFFFF.toInt(),
    commercials = commercials,
)

fun month(vararg cells: ScheduleCell): MonthSchedule =
    MonthSchedule(year = 2026, month = 7, cells = cells.toList())

/** One ROW of the month's grid, as the repository returns it. */
fun breakSlot(hour: Int, minute: Int = 0): BreakSlotInfo = BreakSlotInfo(
    time = LocalTime(hour, minute),
    zone = "DEFAULT",
    zoneColorArgb = 0,
)

// ── fakes over the domain interfaces (mandatory in KMP) ──────────────────

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

    override suspend fun getBreaks(
        year: Int,
        month: Int,
        mode: GridViewMode,
    ): DataResult<List<BreakSlotInfo>, DataError.Network> {
        breakLoads += Triple(year, month, mode)
        return breaksResult
    }

    override suspend fun getMonth(year: Int, month: Int): DataResult<MonthSchedule, DataError.Network> =
        monthResult
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
    val adds = mutableListOf<Triple<Long, LocalTime, LocalDate>>()
    val removes = mutableListOf<Pair<LocalTime, LocalDate>>()
    val reorders = mutableListOf<Triple<LocalTime, LocalDate, List<Long>>>()

    override fun clear() { clears++ }
    override fun loadMonth(year: Int, month: Int) { loads += (year to month) }
    override fun setViewMode(mode: GridViewMode) { viewModes += mode }
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
    override val stations: List<StationAccess> = emptyList(),
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

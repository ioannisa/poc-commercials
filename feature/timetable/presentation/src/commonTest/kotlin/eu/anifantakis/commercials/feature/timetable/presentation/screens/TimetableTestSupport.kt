package eu.anifantakis.commercials.feature.timetable.presentation.screens

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
import eu.anifantakis.commercials.feature.timetable.domain.model.MonthSchedule
import eu.anifantakis.commercials.feature.timetable.domain.model.PlacedCommercial
import eu.anifantakis.commercials.feature.timetable.domain.model.ScheduleCell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate
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
    breakId: Long = 1L,
    date: LocalDate = TEST_DATE,
    commercials: List<PlacedCommercial> = emptyList(),
): ScheduleCell = ScheduleCell(
    breakId = breakId,
    date = date,
    spotCount = commercials.size,
    totalDurationSeconds = commercials.sumOf { it.durationSeconds },
    zoneColorArgb = 0xFFFFFFFF.toInt(),
    commercials = commercials,
)

fun month(vararg cells: ScheduleCell): MonthSchedule =
    MonthSchedule(year = 2026, month = 7, cells = cells.toList())

// ── fakes over the domain interfaces (mandatory in KMP) ──────────────────

class FakeScheduleRepository : ScheduleRepository {
    var monthResult: DataResult<MonthSchedule, DataError.Network> = DataResult.Success(month())

    override suspend fun getBreaks(): DataResult<List<BreakSlotInfo>, DataError.Network> =
        DataResult.Success(emptyList())

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
    val reorders = mutableListOf<Triple<Long, LocalDate, List<Long>>>()

    override suspend fun add(
        spotId: Long,
        breakId: Long,
        date: LocalDate,
    ): DataResult<PlacedCommercial, DataError.Network> = addResult ?: DataResult.Success(nextAdded)

    override suspend fun remove(placementId: Long): EmptyDataResult<DataError.Network> {
        removedIds += placementId
        return removeResult
    }

    override suspend fun reorder(
        breakId: Long,
        date: LocalDate,
        orderedIds: List<Long>,
    ): EmptyDataResult<DataError.Network> {
        reorders += Triple(breakId, date, orderedIds)
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
    val loads = mutableListOf<Pair<Int, Int>>()
    val adds = mutableListOf<Triple<Long, Long, LocalDate>>()
    val removes = mutableListOf<Pair<Long, LocalDate>>()
    val reorders = mutableListOf<Triple<Long, LocalDate, List<Long>>>()

    override fun clear() { clears++ }
    override fun loadMonth(year: Int, month: Int) { loads += (year to month) }
    override fun add(spotId: Long, breakId: Long, date: LocalDate) { adds += Triple(spotId, breakId, date) }
    override fun removeLast(breakId: Long, date: LocalDate) { removes += (breakId to date) }
    override fun reorder(breakId: Long, date: LocalDate, orderedIds: List<Long>) { reorders += Triple(breakId, date, orderedIds) }
}

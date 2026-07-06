package eu.anifantakis.commercials.feature.schedule_email.presentation

import eu.anifantakis.commercials.core.domain.party_search.Party
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.core.domain.party_search.PartySearchRepository
import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailActivityMonth
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailError
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailLogEntry
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailPreviewRequest
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailSpot
import eu.anifantakis.commercials.feature.schedule_email.domain.ScheduleEmailRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Shared base for the schedule-email ViewModel tests: swaps the main
 * dispatcher and starts the minimal Koin the [BaseGlobalViewModel] default
 * needs. Both the send dialog and the preview screen share the repo fakes.
 */
abstract class ScheduleEmailTestBase {
    protected val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        startKoin { modules(module { single { GlobalStateContainer() } }) }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }
}

fun party(code: String = "CUS1", email: String? = "c@example.gr"): Party =
    Party(code = code, name = "Party $code", email = email)

fun previewRequest(
    spotIds: List<Long> = listOf(1L),
    recipient: String = "c@example.gr",
): EmailPreviewRequest = EmailPreviewRequest(
    year = 2026,
    month = 7,
    clientCode = "CUS1",
    kind = PartyKind.CUSTOMER,
    spotIds = spotIds,
    recipient = recipient,
    personalMessage = null,
)

/** Fake over the schedule-email domain contract - every verb is configurable. */
class FakeScheduleEmailRepository(
    var activityResult: DataResult<List<EmailActivityMonth>, EmailError> =
        DataResult.Success(listOf(EmailActivityMonth(year = 2026, month = 7, placements = 5))),
    var spotsResult: DataResult<List<EmailSpot>, EmailError> =
        DataResult.Success(listOf(EmailSpot(spotId = 1L, description = "Spot 1", placements = 2))),
    var historyResult: DataResult<List<EmailLogEntry>, EmailError> = DataResult.Success(emptyList()),
    var previewResult: DataResult<String, EmailError> = DataResult.Success("<html/>"),
    var sendResult: DataResult<String, EmailError> = DataResult.Success("SENT ok"),
) : ScheduleEmailRepository {
    var lastPreviewRequest: EmailPreviewRequest? = null
    var sendCalls = 0

    override suspend fun activity(clientCode: String, kind: PartyKind) = activityResult

    override suspend fun spots(year: Int, month: Int, clientCode: String, kind: PartyKind) = spotsResult

    override suspend fun history(limit: Int, clientCode: String?) = historyResult

    override suspend fun previewHtml(request: EmailPreviewRequest): DataResult<String, EmailError> {
        lastPreviewRequest = request
        return previewResult
    }

    override suspend fun send(request: EmailPreviewRequest): DataResult<String, EmailError> {
        sendCalls++
        return sendResult
    }
}

class FakePartySearchRepository(
    var results: List<Party> = emptyList(),
) : PartySearchRepository {
    var searchCalls = 0
    override suspend fun search(query: String, kind: PartyKind): DataResult<List<Party>, DataError.Network> {
        searchCalls++
        return DataResult.Success(results)
    }
}

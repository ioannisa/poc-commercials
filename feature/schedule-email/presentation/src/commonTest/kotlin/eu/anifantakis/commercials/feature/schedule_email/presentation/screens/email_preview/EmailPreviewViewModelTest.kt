package eu.anifantakis.commercials.feature.schedule_email.presentation.screens.email_preview

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.feature.schedule_email.domain.EmailError
import eu.anifantakis.commercials.feature.schedule_email.presentation.FakeScheduleEmailRepository
import eu.anifantakis.commercials.feature.schedule_email.presentation.ScheduleEmailTestBase
import eu.anifantakis.commercials.feature.schedule_email.presentation.previewRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The preview screen's own VM: loads the exact HTML on start and owns the real
 * send, emitting [EmailPreviewEffect.Sent] on success. Fake repo over the
 * domain contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailPreviewViewModelTest : ScheduleEmailTestBase() {

    private val request = previewRequest()

    @Test
    fun loadsThePreviewHtmlOnStart() = runTest(testDispatcher) {
        val vm = EmailPreviewViewModel(request, FakeScheduleEmailRepository(previewResult = DataResult.Success("<h1>hi</h1>")))
        advanceUntilIdle()

        assertEquals("<h1>hi</h1>", vm.state.html)
        assertFalse(vm.state.loading)
    }

    @Test
    fun previewFailureSurfacesTheServerMessageVerbatim() = runTest(testDispatcher) {
        val vm = EmailPreviewViewModel(request, FakeScheduleEmailRepository(previewResult = DataResult.Failure(EmailError.Server("no spots"))))
        advanceUntilIdle()

        assertEquals(UiText.Dynamic("no spots"), vm.state.error)
        assertFalse(vm.state.loading)
    }

    @Test
    fun sendSuccessEmitsTheSentEffectWithTheStatusLine() = runTest(testDispatcher) {
        val repo = FakeScheduleEmailRepository(sendResult = DataResult.Success("SENT to 1"))
        val vm = EmailPreviewViewModel(request, repo)
        advanceUntilIdle()

        val effects = mutableListOf<EmailPreviewEffect>()
        val job = launch { vm.events.collect { effects += it } }

        vm.onAction(EmailPreviewIntent.Send)
        advanceUntilIdle()

        assertEquals(listOf<EmailPreviewEffect>(EmailPreviewEffect.Sent("SENT to 1")), effects)
        assertEquals(1, repo.sendCalls)
        job.cancel()
    }

    @Test
    fun sendFailureSetsErrorAndReenablesTheButton() = runTest(testDispatcher) {
        val vm = EmailPreviewViewModel(request, FakeScheduleEmailRepository(sendResult = DataResult.Failure(EmailError.Server("smtp down"))))
        advanceUntilIdle()

        vm.onAction(EmailPreviewIntent.Send)
        advanceUntilIdle()

        assertEquals(UiText.Dynamic("smtp down"), vm.state.error)
        assertFalse(vm.state.sending, "the send button re-enables so the operator can retry")
    }
}

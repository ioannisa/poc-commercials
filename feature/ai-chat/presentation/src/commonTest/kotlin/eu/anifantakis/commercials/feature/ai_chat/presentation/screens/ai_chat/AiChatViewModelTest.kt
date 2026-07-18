package eu.anifantakis.commercials.feature.ai_chat.presentation.screens.ai_chat

import eu.anifantakis.commercials.core.domain.auth.AiChatProviderOption
import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatMessage
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatPreferences
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatReply
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatRepository
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatRole
import eu.anifantakis.commercials.feature.ai_chat.domain.AiToolStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The AI-assistant chat VM: the session catalog drives the provider/model
 * selection (default = first provider, its first model; switching provider
 * resets the model), sending appends the user turn immediately, locks the
 * input while the request runs, appends the assistant reply (with its tool
 * trail) on success, and surfaces a UiText error - keeping the user's turn in
 * the transcript - on failure.
 */
class AiChatViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val catalog = listOf(
        AiChatProviderOption("anthropic", listOf("claude-sonnet-5", "claude-haiku-4-5")),
        AiChatProviderOption("gemini", listOf("gemini-flash", "gemini-pro")),
    )

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

    private class FakeAiChatPreferences(
        override var provider: String = "",
        override var model: String = "",
        override var panelWidthDp: Int = AiChatPreferences.DEFAULT_PANEL_WIDTH_DP,
    ) : AiChatPreferences

    private class FakeAiChatRepository(
        var result: DataResult<AiChatReply, RemoteError> =
            DataResult.Success(AiChatReply("Hello!", emptyList())),
    ) : AiChatRepository {
        var calls = 0
        var lastHistory: List<AiChatMessage> = emptyList()
        var lastProvider: String? = null
        var lastModel: String? = null

        override suspend fun send(
            history: List<AiChatMessage>,
            provider: String,
            model: String,
        ): DataResult<AiChatReply, RemoteError> {
            calls++
            lastHistory = history
            lastProvider = provider
            lastModel = model
            return result
        }
    }

    @Test
    fun initSelectsDefaultProviderAndModel() = runTest(testDispatcher) {
        val vm = AiChatViewModel(FakeAiChatRepository(), FakeAiChatPreferences())

        vm.onAction(AiChatIntent.Init(catalog))

        // The catalog's order is the server's (default first) - so is the pick.
        assertEquals("anthropic", vm.state.selectedProviderId)
        assertEquals("claude-sonnet-5", vm.state.selectedModel)
        assertEquals(2, vm.state.providers.size)
    }

    @Test
    fun selectProviderResetsModelToThatProvidersFirst() = runTest(testDispatcher) {
        val vm = AiChatViewModel(FakeAiChatRepository(), FakeAiChatPreferences())
        vm.onAction(AiChatIntent.Init(catalog))

        vm.onAction(AiChatIntent.SelectProvider("gemini"))
        assertEquals("gemini", vm.state.selectedProviderId)
        assertEquals("gemini-flash", vm.state.selectedModel)

        vm.onAction(AiChatIntent.SelectModel("gemini-pro"))
        assertEquals("gemini-pro", vm.state.selectedModel)

        // A pick outside the catalog is ignored - the server would 400 it anyway.
        vm.onAction(AiChatIntent.SelectProvider("openai"))
        assertEquals("gemini", vm.state.selectedProviderId)
        vm.onAction(AiChatIntent.SelectModel("claude-sonnet-5"))
        assertEquals("gemini-pro", vm.state.selectedModel)
    }

    @Test
    fun reInitKeepsAStillValidSelection() = runTest(testDispatcher) {
        val vm = AiChatViewModel(FakeAiChatRepository(), FakeAiChatPreferences())
        vm.onAction(AiChatIntent.Init(catalog))
        vm.onAction(AiChatIntent.SelectProvider("gemini"))
        vm.onAction(AiChatIntent.SelectModel("gemini-pro"))

        // The keep-alive re-supplies a catalog that still contains the picks.
        vm.onAction(AiChatIntent.Init(catalog.reversed()))
        assertEquals("gemini", vm.state.selectedProviderId)
        assertEquals("gemini-pro", vm.state.selectedModel)

        // ...and one that no longer does: fall back to the new default.
        val anthropicOnly = listOf(catalog.first())
        vm.onAction(AiChatIntent.Init(anthropicOnly))
        assertEquals("anthropic", vm.state.selectedProviderId)
        assertEquals("claude-sonnet-5", vm.state.selectedModel)
    }

    @Test
    fun sendCarriesTheSelectedProviderAndModel() = runTest(testDispatcher) {
        val repo = FakeAiChatRepository(
            result = DataResult.Success(AiChatReply("3 spots", listOf(AiToolStep("list_breaks", isError = false)))),
        )
        val vm = AiChatViewModel(repo, FakeAiChatPreferences())
        vm.onAction(AiChatIntent.Init(catalog))
        vm.onAction(AiChatIntent.SelectProvider("gemini"))

        vm.onAction(AiChatIntent.InputChanged("what plays tomorrow?"))
        vm.onAction(AiChatIntent.Send)

        assertEquals(1, repo.calls)
        assertEquals("gemini", repo.lastProvider)
        assertEquals("gemini-flash", repo.lastModel)
        // The request carried the user's turn...
        assertEquals(AiChatRole.USER, repo.lastHistory.last().role)
        assertEquals("what plays tomorrow?", repo.lastHistory.last().text)
        // ...and the transcript now holds user + assistant, input cleared, idle.
        val state = vm.state
        assertEquals(2, state.messages.size)
        assertEquals(AiChatRole.ASSISTANT, state.messages.last().role)
        assertEquals("3 spots", state.messages.last().text)
        assertEquals(listOf(AiToolStep("list_breaks", isError = false)), state.messages.last().steps)
        assertEquals("", state.input)
        assertFalse(state.busy)
        assertNull(state.error)
    }

    @Test
    fun failureKeepsUserTurnAndShowsError() = runTest(testDispatcher) {
        val repo = FakeAiChatRepository(result = DataResult.Failure(RemoteError.Server("boom")))
        val vm = AiChatViewModel(repo, FakeAiChatPreferences())
        vm.onAction(AiChatIntent.Init(catalog))

        vm.onAction(AiChatIntent.InputChanged("hi"))
        vm.onAction(AiChatIntent.Send)

        val state = vm.state
        assertEquals(1, state.messages.size)          // the user's turn stays visible
        assertEquals(AiChatRole.USER, state.messages.last().role)
        assertNotNull(state.error)
        assertFalse(state.busy)
    }

    @Test
    fun blankInputOrMissingSelectionDoesNotSend() = runTest(testDispatcher) {
        val repo = FakeAiChatRepository()
        val vm = AiChatViewModel(repo, FakeAiChatPreferences())
        vm.onAction(AiChatIntent.Init(catalog))

        vm.onAction(AiChatIntent.InputChanged("   "))
        vm.onAction(AiChatIntent.Send)
        assertEquals(0, repo.calls)
        assertTrue(vm.state.messages.isEmpty())

        // No catalog (feature hidden mid-session): Send must be a no-op too.
        val bare = AiChatViewModel(repo, FakeAiChatPreferences())
        bare.onAction(AiChatIntent.InputChanged("hello"))
        bare.onAction(AiChatIntent.Send)
        assertEquals(0, repo.calls)
        assertFalse(bare.state.canSend)
    }

    @Test
    fun initRestoresThePersistedSelection() = runTest(testDispatcher) {
        val prefs = FakeAiChatPreferences(provider = "gemini", model = "gemini-pro")
        val vm = AiChatViewModel(FakeAiChatRepository(), prefs)

        vm.onAction(AiChatIntent.Init(catalog))

        // The silently persisted pick wins over the catalog default...
        assertEquals("gemini", vm.state.selectedProviderId)
        assertEquals("gemini-pro", vm.state.selectedModel)

        // ...but a STALE pick (provider gone from server.yaml) falls through.
        val vm2 = AiChatViewModel(FakeAiChatRepository(), FakeAiChatPreferences(provider = "openai", model = "gpt-x"))
        vm2.onAction(AiChatIntent.Init(catalog))
        assertEquals("anthropic", vm2.state.selectedProviderId)
        assertEquals("claude-sonnet-5", vm2.state.selectedModel)
    }

    @Test
    fun selectionChangesPersistSilently() = runTest(testDispatcher) {
        val prefs = FakeAiChatPreferences()
        val vm = AiChatViewModel(FakeAiChatRepository(), prefs)
        vm.onAction(AiChatIntent.Init(catalog))

        vm.onAction(AiChatIntent.SelectProvider("gemini"))
        assertEquals("gemini", prefs.provider)
        assertEquals("gemini-flash", prefs.model)   // provider switch resets to ITS default

        vm.onAction(AiChatIntent.SelectModel("gemini-pro"))
        assertEquals("gemini-pro", prefs.model)
    }

    @Test
    fun clearResetsTheConversationButKeepsTheSelection() = runTest(testDispatcher) {
        val repo = FakeAiChatRepository()
        val vm = AiChatViewModel(repo, FakeAiChatPreferences())
        vm.onAction(AiChatIntent.Init(catalog))
        vm.onAction(AiChatIntent.SelectProvider("gemini"))

        vm.onAction(AiChatIntent.InputChanged("hello"))
        vm.onAction(AiChatIntent.Send)
        assertEquals(2, vm.state.messages.size)

        vm.onAction(AiChatIntent.Clear)
        assertTrue(vm.state.messages.isEmpty())
        assertEquals("", vm.state.input)
        assertEquals("gemini", vm.state.selectedProviderId)
        assertEquals("gemini-flash", vm.state.selectedModel)
    }
}

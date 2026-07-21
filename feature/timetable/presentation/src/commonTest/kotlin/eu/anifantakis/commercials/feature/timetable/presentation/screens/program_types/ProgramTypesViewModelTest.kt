package eu.anifantakis.commercials.feature.timetable.presentation.screens.program_types

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.presentation.global_state.GlobalEffect
import eu.anifantakis.commercials.feature.timetable.domain.model.Program
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeProgramsRepository
import eu.anifantakis.commercials.feature.timetable.presentation.screens.FakeTimetableCommon
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The «Τύποι Προγράμματος» catalog window.
 *
 * The catalog and the open dialog are the window's own; the armed BRUSH is
 * the flow's, because the grid's 'a' key and the «Προβολή Βάσει… Πρόγραμμα»
 * filter read it. These tests are mostly about that seam - the CRUD itself
 * is a thin pass-through to the repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgramTypesViewModelTest : TimetableTestBase() {

    private val repository = FakeProgramsRepository()
    private val common = FakeTimetableCommon()

    private fun vm() = ProgramTypesViewModel(repository, common)

    private fun program(id: Long, name: String = "News", color: Int? = null) =
        Program(id = id, name = name, colorArgb = color)

    @Test
    fun loadsTheCatalogOnStart() = runTest(testDispatcher) {
        repository.programs = listOf(program(1), program(2, "Sports"))

        val vm = vm()
        advanceUntilIdle()

        assertEquals(2, vm.state.programs.size)
    }

    @Test
    fun selectingArmsTheBrushThroughTheFlow() = runTest(testDispatcher) {
        repository.programs = listOf(program(7, "News"))
        val vm = vm()
        advanceUntilIdle()

        vm.onAction(ProgramTypesIntent.Select(7))
        advanceUntilIdle()

        assertEquals(
            program(7, "News"),
            common.programSelections.last(),
            "the whole Program travels - the grid header draws its name and colour",
        )
        assertEquals(7L, vm.state.armedId, "and it comes back down merged")
    }

    /**
     * The risk this split introduced. Deleting the armed programme used to
     * clear the brush inside ONE ViewModel; now the console and the grid are
     * separate, so the disarm has to travel through the flow - otherwise the
     * grid stays scoped to a programme that no longer exists and the operator
     * sees an unexplained empty month.
     */
    @Test
    fun removingTheArmedProgrammeDisarmsTheBrush() = runTest(testDispatcher) {
        repository.programs = listOf(program(7, "News"))
        val vm = vm()
        advanceUntilIdle()
        vm.onAction(ProgramTypesIntent.Select(7))
        advanceUntilIdle()
        assertEquals(7L, common.commonState.value.armedProgram?.id)

        vm.onAction(ProgramTypesIntent.Remove)
        advanceUntilIdle()

        assertNull(
            common.commonState.value.armedProgram,
            "the deleted programme must not stay armed - the grid would filter on a dead id",
        )
        assertEquals(listOf(7L), repository.removed)
        assertTrue(vm.state.programs.isEmpty())
    }

    /** A rename must reach the grid: cells render the programme's NAME. */
    @Test
    fun renamingRefreshesTheMonthAndTheArmedCopy() = runTest(testDispatcher) {
        repository.programs = listOf(program(7, "News"))
        val vm = vm()
        advanceUntilIdle()
        vm.onAction(ProgramTypesIntent.Select(7))
        advanceUntilIdle()

        vm.onAction(ProgramTypesIntent.Rename("Headlines"))
        advanceUntilIdle()

        assertEquals(1, common.refreshes, "the month repaints - cells show the name")
        assertEquals(
            "Headlines",
            common.commonState.value.armedProgram?.name,
            "the armed copy is re-pushed from the fresh catalog, not left stale",
        )
    }

    @Test
    fun creatingArmsTheNewProgramme() = runTest(testDispatcher) {
        val vm = vm()
        advanceUntilIdle()

        vm.onAction(ProgramTypesIntent.Create("Weather", 0xFF00FF00.toInt()))
        advanceUntilIdle()

        assertEquals(listOf<Pair<String, Int?>>("Weather" to 0xFF00FF00.toInt()), repository.created)
        assertEquals(
            "Weather",
            common.commonState.value.armedProgram?.name,
            "a freshly created programme is armed at once - that is what it was created FOR",
        )
        assertNull(vm.state.dialog, "and the dialog closes")
    }

    /** ΔΙΟΡΘ/ΑΦΑΙΡ/Χρώμα act ON a selection; ΠΡΟΣΘ never needs one. */
    @Test
    fun dialogsThatNeedASelectionRefuseWithoutOne() = runTest(testDispatcher) {
        val effects = mutableListOf<GlobalEffect>()
        backgroundScope.launch { globalContainer.effects.collect { effects += it } }
        val vm = vm()
        advanceUntilIdle()

        vm.onAction(ProgramTypesIntent.OpenDialog(ProgramDialog.EDIT))
        advanceUntilIdle()

        assertNull(vm.state.dialog)
        assertEquals(1, effects.count { it is GlobalEffect.SnackBarMessage })

        vm.onAction(ProgramTypesIntent.OpenDialog(ProgramDialog.ADD))
        assertEquals(ProgramDialog.ADD, vm.state.dialog, "ΠΡΟΣΘ opens with nothing selected")
    }

    /**
     * The reason this feature exists: a retired programme still paints April's
     * cells but is invisible to the active-only catalog. «Όλα» surfaces it (so
     * the filter can now find it), and the row's checkbox restores it.
     */
    @Test
    fun theAllViewSurfacesRetiredProgrammesAndTogglesThem() = runTest(testDispatcher) {
        repository.programs = listOf(
            program(1, "News"),
            program(2, "Εορταστικό").copy(active = false),
        )
        val vm = vm()
        advanceUntilIdle()

        // Ενεργά (default): the retired one is absent - the bug the user hit.
        assertEquals(listOf("News"), vm.state.programs.map { it.name })

        // Όλα: both appear, and the filter can now reach the retired one.
        vm.onAction(ProgramTypesIntent.ShowChanged(ProgramShow.ALL))
        advanceUntilIdle()
        assertEquals(2, vm.state.programs.size)
        assertFalse(vm.state.programs.first { it.id == 2L }.active)
        vm.onAction(ProgramTypesIntent.FilterChanged("Εο"))
        assertEquals(listOf("Εορταστικό"), vm.state.visible.map { it.name })

        // The checkbox restores it: setActive(true), and the flag flips.
        vm.onAction(ProgramTypesIntent.SetActive(2L, true))
        advanceUntilIdle()
        assertEquals(listOf(2L to true), repository.activeSet)
        assertTrue(vm.state.programs.first { it.id == 2L }.active)
    }

    @Test
    fun aFailedLoadSurfacesOnceThroughTheGlobalSnackbar() = runTest(testDispatcher) {
        repository.listFailure = DataError.Network.NO_INTERNET
        val effects = mutableListOf<GlobalEffect>()
        backgroundScope.launch { globalContainer.effects.collect { effects += it } }

        vm()
        advanceUntilIdle()

        assertEquals(1, effects.count { it is GlobalEffect.SnackBarMessage })
    }
}

package eu.anifantakis.commercials.feature.timetable.presentation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import eu.anifantakis.commercials.core.presentation.helper.navConfigOf
import eu.anifantakis.commercials.core.presentation.helper.navHierarchy
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommonViewModel
import eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail.CommercialDetailScreenRoot
import eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable.TimetableScreenRoot
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The timetable flow appears in the ROOT graph as ONE route (kmp-developer
 * Wiring A): the parent entry owns the [TimetableCommonViewModel] via the
 * ViewModelStore decorator, so the flow's shared state and every step
 * ViewModel live and die with this single entry - no manual owners.
 */
@Serializable
sealed interface TimetableNavType : NavKey {
    @Serializable
    data object TimetableFlow : TimetableNavType
}

/** Internal steps - known only inside the flow, invisible to the root graph. */
@Serializable
sealed interface TimetableStepNavType : NavKey {
    @Serializable
    data object Grid : TimetableStepNavType

    @Serializable
    data class CommercialDetail(
        val breakId: Long,
        val breakTime: String,
        val date: LocalDate,
        val spotCount: Int,
    ) : TimetableStepNavType
}

// Serializers derived from the sealed hierarchy's CLOSED generated
// serializer (all targets, compile-time) - adding a step route needs no
// registration change here, ever.
private val stepNavConfig = navConfigOf(navHierarchy<TimetableStepNavType>())

/**
 * ONE root entry for the whole flow. App-owned concerns (the schedule-email
 * dialog, logout, preferences) come in as callbacks; navigation BETWEEN the
 * flow's steps stays internal to [TimetableFlowHost].
 */
fun EntryProviderScope<NavKey>.timetableEntries(
    onOpenEmailDialog: () -> Unit,
    onLogout: () -> Unit,
    onPreferences: () -> Unit,
) {
    entry<TimetableNavType.TimetableFlow> {
        // THIS entry is the CommonViewModel's owner: popping the flow
        // destroys it, the nested stack, and every step ViewModel.
        val common = koinViewModel<TimetableCommonViewModel>()
        TimetableFlowHost(
            common = common,
            onOpenEmailDialog = onOpenEmailDialog,
            onLogout = onLogout,
            onPreferences = onPreferences,
        )
    }
}

/**
 * Hosts the flow's nested back stack (Grid ⇄ Detail). Each step resolves
 * its OWN ViewModel and hands it the [TimetableCommon] contract - matched
 * by type from parametersOf. Pure state-sharing flow: there are no
 * commonEffects to collect.
 */
@Composable
private fun TimetableFlowHost(
    common: TimetableCommonViewModel,
    onOpenEmailDialog: () -> Unit,
    onLogout: () -> Unit,
    onPreferences: () -> Unit,
) {
    val stepStack = rememberNavBackStack(stepNavConfig, TimetableStepNavType.Grid)

    NavDisplay(
        backStack = stepStack,
        onBack = { if (stepStack.size > 1) stepStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),   // both decorators AGAIN - nested display
            rememberViewModelStoreNavEntryDecorator(),        // per-STEP ViewModel scoping
        ),
        transitionSpec = {
            slideInHorizontally { it } + fadeIn() togetherWith
                    slideOutHorizontally { -it } + fadeOut()
        },
        popTransitionSpec = {
            slideInHorizontally { -it } + fadeIn() togetherWith
                    slideOutHorizontally { it } + fadeOut()
        },
        entryProvider = entryProvider {

            entry<TimetableStepNavType.Grid> {
                TimetableScreenRoot(
                    viewModel = koinViewModel { parametersOf(common) },
                    onOpenDetail = { breakId, breakTime, date, spotCount ->
                        stepStack.add(
                            TimetableStepNavType.CommercialDetail(breakId, breakTime, date, spotCount)
                        )
                    },
                    onOpenEmailDialog = onOpenEmailDialog,
                    onLogout = onLogout,
                    onPreferences = onPreferences,
                )
            }

            entry<TimetableStepNavType.CommercialDetail> { route ->
                CommercialDetailScreenRoot(
                    // The nav arguments are the ViewModel's constructor - the
                    // Root only wires the VM and the navigation callbacks.
                    viewModel = koinViewModel(
                        key = "commercial-detail-${route.breakId}-${route.date}",
                    ) { parametersOf(route.breakId, route.breakTime, route.date, common) },
                    onBack = { stepStack.removeLastOrNull() },
                    // Προηγούμενο/Επόμενο: RE-TARGET the top entry to the
                    // sibling break (replace, not push - Back still returns
                    // to the grid, exactly like the legacy Break Console).
                    onNavigateToBreak = { breakId, breakTime, spotCount ->
                        stepStack[stepStack.lastIndex] =
                            TimetableStepNavType.CommercialDetail(breakId, breakTime, route.date, spotCount)
                    },
                )
            }
        }
    )
}

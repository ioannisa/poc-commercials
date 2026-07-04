package eu.anifantakis.commercials.feature.timetable.presentation

import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.anifantakis.commercials.core.presentation.navigation.Navigator
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TimetableCommonViewModel
import eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail.CommercialDetailScreenRoot
import eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable.TimetableScreenRoot
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
sealed interface TimetableNavType : NavKey {
    @Serializable
    data object Grid : TimetableNavType

    @Serializable
    data class CommercialDetail(
        val breakId: Long,
        val breakTime: String,
        val date: LocalDate,
        val spotCount: Int,
    ) : TimetableNavType
}

/**
 * The grid and the break-detail console. Both screens have their own
 * ViewModel; the flow-shared [TimetableCommonViewModel] (the month's cells
 * + all placement I/O) is resolved from [flowOwner] so BOTH entries get the
 * SAME instance, and is handed to each screen ViewModel via parametersOf.
 * App-owned concerns (the schedule-email dialog, logout, preferences) come
 * in as callbacks.
 */
fun EntryProviderScope<NavKey>.timetableEntries(
    navigator: Navigator,
    flowOwner: ViewModelStoreOwner,
    onOpenEmailDialog: () -> Unit,
    onLogout: () -> Unit,
    onPreferences: () -> Unit,
) {
    entry<TimetableNavType.Grid> {
        val common = koinViewModel<TimetableCommonViewModel>(viewModelStoreOwner = flowOwner)
        TimetableScreenRoot(
            viewModel = koinViewModel { parametersOf(common) },
            onOpenDetail = { breakId, breakTime, date, spotCount ->
                navigator.navigate(TimetableNavType.CommercialDetail(breakId, breakTime, date, spotCount))
            },
            onOpenEmailDialog = onOpenEmailDialog,
            onLogout = onLogout,
            onPreferences = onPreferences,
        )
    }

    entry<TimetableNavType.CommercialDetail> { route ->
        val common = koinViewModel<TimetableCommonViewModel>(viewModelStoreOwner = flowOwner)
        CommercialDetailScreenRoot(
            breakId = route.breakId,
            breakTime = route.breakTime,
            date = route.date,
            spotCount = route.spotCount,
            viewModel = koinViewModel(
                key = "commercial-detail-${route.breakId}-${route.date}",
            ) { parametersOf(route.breakId, route.date, common) },
            onBack = { navigator.goBack() },
        )
    }
}

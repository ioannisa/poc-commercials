package eu.anifantakis.commercials.feature.timetable.presentation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.anifantakis.commercials.core.presentation.navigation.Navigator
import eu.anifantakis.commercials.feature.timetable.presentation.screens.commercial_detail.CommercialDetailScreenRoot
import eu.anifantakis.commercials.feature.timetable.presentation.screens.timetable.TimetableScreenRoot
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

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
 * ViewModel; the shared truth (the month's cells) lives in
 * ScheduleCellsStore behind them. App-owned concerns (the schedule-email
 * dialog, logout, preferences) come in as callbacks.
 */
fun EntryProviderScope<NavKey>.timetableEntries(
    navigator: Navigator,
    onOpenEmailDialog: () -> Unit,
    onLogout: () -> Unit,
    onPreferences: () -> Unit,
) {
    entry<TimetableNavType.Grid> {
        TimetableScreenRoot(
            onOpenDetail = { breakId, breakTime, date, spotCount ->
                navigator.navigate(TimetableNavType.CommercialDetail(breakId, breakTime, date, spotCount))
            },
            onOpenEmailDialog = onOpenEmailDialog,
            onLogout = onLogout,
            onPreferences = onPreferences,
        )
    }

    entry<TimetableNavType.CommercialDetail> { route ->
        CommercialDetailScreenRoot(
            breakId = route.breakId,
            breakTime = route.breakTime,
            date = route.date,
            spotCount = route.spotCount,
            onBack = { navigator.goBack() },
        )
    }
}

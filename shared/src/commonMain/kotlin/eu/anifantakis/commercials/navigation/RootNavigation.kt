package eu.anifantakis.commercials.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import eu.anifantakis.commercials.admin.DatabasesScreen
import eu.anifantakis.commercials.admin.MigrationScreen
import eu.anifantakis.commercials.admin.UserManagementScreen
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.presentation.login.LoginScreenRoot
import eu.anifantakis.commercials.feature.timetable.presentation.commercial_detail.CommercialDetailScreenRoot
import eu.anifantakis.commercials.feature.timetable.presentation.timetable.TimetableScreenRoot
import eu.anifantakis.commercials.prefs.UserPreferences
import eu.anifantakis.commercials.screens.PreferencesScreen
import eu.anifantakis.commercials.screens.SendScheduleEmailDialog
import kotlinx.coroutines.launch
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.koin.compose.koinInject

// Required for non-JVM platforms (iOS, JS, WASM) — registers route serializers explicitly
// since they cannot rely on reflection-based discovery.
private val navConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(CommercialNavRoute.Login::class, CommercialNavRoute.Login.serializer())
            subclass(CommercialNavRoute.Timetable::class, CommercialNavRoute.Timetable.serializer())
            subclass(CommercialNavRoute.Preferences::class, CommercialNavRoute.Preferences.serializer())
            subclass(CommercialNavRoute.UserManagement::class, CommercialNavRoute.UserManagement.serializer())
            subclass(CommercialNavRoute.Migration::class, CommercialNavRoute.Migration.serializer())
            subclass(CommercialNavRoute.Databases::class, CommercialNavRoute.Databases.serializer())
            subclass(CommercialNavRoute.CommercialDetail::class, CommercialNavRoute.CommercialDetail.serializer())
        }
    }
}

@Composable
fun RootNavigation() {
    val scope = rememberCoroutineScope()
    val authSession = koinInject<AuthSession>()
    val authRepository = koinInject<AuthRepository>()
    val prefs = koinInject<UserPreferences>()

    // Token persists (no expiry), so a returning user skips the login screen
    val backStack = rememberNavBackStack(
        navConfig,
        if (authSession.isLoggedIn) CommercialNavRoute.Timetable else CommercialNavRoute.Login
    )

    // If the session becomes invalid at any point (e.g. the server rejects our
    // token with 401 - the auth layer clears it), bounce back to Login instead
    // of sitting on a screen that can't load data.
    val authRevision = authSession.revision
    LaunchedEffect(authRevision) {
        if (!authSession.isLoggedIn && backStack.lastOrNull() != CommercialNavRoute.Login) {
            backStack.clear()
            backStack.add(CommercialNavRoute.Login)
        }
    }

    // The schedule-email dialog belongs to its own feature; the timetable
    // only carries the launch button, so the app layer renders it here.
    var showEmailDialog by remember { mutableStateOf(false) }
    if (showEmailDialog) {
        SendScheduleEmailDialog(onDismiss = { showEmailDialog = false })
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),   // must be first
            rememberViewModelStoreNavEntryDecorator()
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

            entry<CommercialNavRoute.Login> {
                LoginScreenRoot(
                    onLoggedIn = {
                        backStack.clear()
                        backStack.add(CommercialNavRoute.Timetable)
                    }
                )
            }

            entry<CommercialNavRoute.Timetable> {
                TimetableScreenRoot(
                    showSpotTimes = prefs.showSpotTimes,
                    onToggleShowTimes = { prefs.showSpotTimes = !prefs.showSpotTimes },
                    onOpenDetail = { breakId, breakTime, date, spotCount ->
                        backStack.add(
                            CommercialNavRoute.CommercialDetail(
                                breakId = breakId,
                                breakTime = breakTime,
                                date = date,
                                spotCount = spotCount
                            )
                        )
                    },
                    onOpenEmailDialog = { showEmailDialog = true },
                    onLogout = {
                        scope.launch {
                            authRepository.logout()   // revokes the token server-side, clears the session
                            backStack.clear()
                            backStack.add(CommercialNavRoute.Login)
                        }
                    },
                    onPreferences = {
                        backStack.add(CommercialNavRoute.Preferences)
                    }
                )
            }

            entry<CommercialNavRoute.Preferences> {
                PreferencesScreen(
                    onBack = { backStack.removeLastOrNull() },
                    onManageUsers = { backStack.add(CommercialNavRoute.UserManagement) },
                    onMigration = { backStack.add(CommercialNavRoute.Migration) },
                    onDatabases = { backStack.add(CommercialNavRoute.Databases) }
                )
            }

            entry<CommercialNavRoute.UserManagement> {
                UserManagementScreen(
                    onBack = { backStack.removeLastOrNull() }
                )
            }

            entry<CommercialNavRoute.Migration> {
                MigrationScreen(
                    onBack = { backStack.removeLastOrNull() }
                )
            }

            entry<CommercialNavRoute.Databases> {
                DatabasesScreen(
                    onBack = { backStack.removeLastOrNull() }
                )
            }

            entry<CommercialNavRoute.CommercialDetail> { route ->
                // Own ViewModel per screen; the shared truth (the cell's
                // commercials) flows through ScheduleCellsStore.
                CommercialDetailScreenRoot(
                    breakId = route.breakId,
                    breakTime = route.breakTime,
                    date = route.date,
                    spotCount = route.spotCount,
                    onBack = { backStack.removeLastOrNull() }
                )
            }

        }
    )
}

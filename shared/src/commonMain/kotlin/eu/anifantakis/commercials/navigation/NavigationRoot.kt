package eu.anifantakis.commercials.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.presentation.helper.rememberFlowViewModelStoreOwner
import eu.anifantakis.commercials.core.presentation.navigation.Navigator
import eu.anifantakis.commercials.core.presentation.scaffold.ApplicationScaffold
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.presentation.AuthNavType
import eu.anifantakis.commercials.feature.auth.presentation.screens.change_password.ChangePasswordDialogRoot
import eu.anifantakis.commercials.feature.auth.presentation.screens.recovery_codes.RecoveryCodesDialogRoot
import eu.anifantakis.commercials.feature.auth.presentation.authEntries
import eu.anifantakis.commercials.feature.databases.presentation.DatabasesNavType
import eu.anifantakis.commercials.feature.databases.presentation.databasesEntries
import eu.anifantakis.commercials.feature.migration_console.presentation.MigrationNavType
import eu.anifantakis.commercials.feature.migration_console.presentation.migrationEntries
import eu.anifantakis.commercials.feature.preferences.presentation.PreferencesNavType
import eu.anifantakis.commercials.feature.preferences.presentation.preferencesEntries
import eu.anifantakis.commercials.feature.schedule_email.presentation.screens.send_schedule_email.SendScheduleEmailDialogRoot
import eu.anifantakis.commercials.feature.timetable.presentation.TimetableNavType
import eu.anifantakis.commercials.feature.timetable.presentation.timetableEntries
import eu.anifantakis.commercials.feature.user_management.presentation.UserManagementNavType
import eu.anifantakis.commercials.feature.user_management.presentation.userManagementEntries
import kotlinx.coroutines.launch
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.koin.compose.koinInject

// Required for non-JVM platforms (iOS, JS, WASM) — registers every feature's
// route serializers explicitly since they cannot rely on reflection-based
// discovery. New feature => new subclass lines here.
private val navConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(AuthNavType.Login::class, AuthNavType.Login.serializer())
            subclass(TimetableNavType.Grid::class, TimetableNavType.Grid.serializer())
            subclass(TimetableNavType.CommercialDetail::class, TimetableNavType.CommercialDetail.serializer())
            subclass(PreferencesNavType.Preferences::class, PreferencesNavType.Preferences.serializer())
            subclass(UserManagementNavType.UserManagement::class, UserManagementNavType.UserManagement.serializer())
            subclass(MigrationNavType.Migration::class, MigrationNavType.Migration.serializer())
            subclass(DatabasesNavType.Databases::class, DatabasesNavType.Databases.serializer())
        }
    }
}

/**
 * The app layer's single assembly point: creates the [Navigator] over a
 * persisted back stack, wraps everything in the [ApplicationScaffold]
 * (global snackbar + loading overlay), hosts the app-level dialogs, and
 * stitches the features' entry providers together. Cross-feature
 * transitions are wired HERE as callbacks - features never see each other.
 */
@Composable
fun NavigationRoot() {
    val scope = rememberCoroutineScope()
    val authSession = koinInject<AuthSession>()
    val authRepository = koinInject<AuthRepository>()

    // Token persists (no expiry), so a returning user skips the login screen
    val backStack = rememberNavBackStack(
        navConfig,
        if (authSession.isLoggedIn) TimetableNavType.Grid else AuthNavType.Login
    )
    val navigator = remember { Navigator(backStack) }

    // If the session becomes invalid at any point (e.g. the server rejects our
    // token with 401 - the auth layer clears it), bounce back to Login instead
    // of sitting on a screen that can't load data.
    val authRevision = authSession.revision
    LaunchedEffect(authRevision) {
        if (!authSession.isLoggedIn && navigator.current() != AuthNavType.Login) {
            navigator.resetTo(AuthNavType.Login)
        }
    }

    // App-level dialogs: each belongs to a feature (own Root + ViewModel),
    // but the launch points live on other features' screens, so the app
    // layer renders them.
    var showEmailDialog by remember { mutableStateOf(false) }
    if (showEmailDialog) {
        SendScheduleEmailDialogRoot(onDismiss = { showEmailDialog = false })
    }
    var showChangePassword by remember { mutableStateOf(false) }
    if (showChangePassword) {
        ChangePasswordDialogRoot(onDismiss = { showChangePassword = false })
    }
    var showRecoveryCodes by remember { mutableStateOf(false) }
    if (showRecoveryCodes) {
        RecoveryCodesDialogRoot(onDismiss = { showRecoveryCodes = false })
    }

    // Flow-shared ViewModel owner: the grid and detail entries resolve the
    // same TimetableCommonViewModel from it (kmp-developer flow scope).
    val timetableFlowOwner = rememberFlowViewModelStoreOwner("owner:TimetableFlow")

    ApplicationScaffold { scaffoldPadding ->
        NavDisplay(
            backStack = navigator.backStack,
            onBack = { navigator.goBack() },
            modifier = Modifier.padding(scaffoldPadding),
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

                authEntries(
                    onLoggedIn = { navigator.resetTo(TimetableNavType.Grid) },
                )

                timetableEntries(
                    navigator = navigator,
                    flowOwner = timetableFlowOwner,
                    onOpenEmailDialog = { showEmailDialog = true },
                    onLogout = {
                        scope.launch {
                            authRepository.logout()   // revokes the token server-side, clears the session
                            navigator.resetTo(AuthNavType.Login)
                        }
                    },
                    onPreferences = { navigator.navigate(PreferencesNavType.Preferences) },
                )

                preferencesEntries(
                    navigator = navigator,
                    isAdmin = { authSession.isAdmin },
                    onChangePassword = { showChangePassword = true },
                    onRecoveryCodes = { showRecoveryCodes = true },
                    onManageUsers = { navigator.navigate(UserManagementNavType.UserManagement) },
                    onMigration = { navigator.navigate(MigrationNavType.Migration) },
                    onDatabases = { navigator.navigate(DatabasesNavType.Databases) },
                )

                userManagementEntries(
                    navigator = navigator,
                    // the super admin sees every hosted station in their session
                    stationChoices = { authSession.stations.map { it.id to it.name } },
                )

                migrationEntries(navigator)

                databasesEntries(navigator)
            }
        )
    }
}

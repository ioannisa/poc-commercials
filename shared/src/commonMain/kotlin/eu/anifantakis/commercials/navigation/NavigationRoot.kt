package eu.anifantakis.commercials.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import eu.anifantakis.commercials.core.data.config.AppConfig
import eu.anifantakis.commercials.core.data.session.AuthSession
import eu.anifantakis.commercials.core.presentation.commands.AppCommand
import eu.anifantakis.commercials.core.presentation.commands.CommandRegistry
import eu.anifantakis.commercials.core.presentation.commands.RegisterAppCommand
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.mediumSpec
import eu.anifantakis.commercials.core.presentation.global_state.GlobalStateContainer
import eu.anifantakis.commercials.core.presentation.helper.navConfigOf
import eu.anifantakis.commercials.core.presentation.helper.navHierarchy
import eu.anifantakis.commercials.core.presentation.navigation.Navigator
import eu.anifantakis.commercials.core.presentation.scaffold.ApplicationScaffold
import eu.anifantakis.commercials.feature.auth.domain.AuthRepository
import eu.anifantakis.commercials.feature.auth.presentation.AuthNavType
import eu.anifantakis.commercials.feature.auth.presentation.screens.api_tokens.ApiTokensDialogRoot
import eu.anifantakis.commercials.feature.auth.presentation.screens.change_password.ChangePasswordDialogRoot
import eu.anifantakis.commercials.feature.user_management.presentation.screens.admin_mcp.AdminMcpDialogRoot
import eu.anifantakis.commercials.feature.user_management.presentation.screens.admin_update.AdminAppUpdateDialogRoot
import eu.anifantakis.commercials.feature.auth.presentation.authEntries
import eu.anifantakis.commercials.feature.databases.presentation.DatabasesNavType
import eu.anifantakis.commercials.feature.databases.presentation.databasesEntries
import eu.anifantakis.commercials.feature.galaxy_bridge.presentation.GalaxyBridgeNavType
import eu.anifantakis.commercials.feature.galaxy_bridge.presentation.galaxyBridgeEntries
import eu.anifantakis.commercials.feature.migration_console.presentation.MigrationNavType
import eu.anifantakis.commercials.feature.migration_console.presentation.migrationEntries
import eu.anifantakis.commercials.feature.preferences.domain.FontSizePreference
import eu.anifantakis.commercials.feature.preferences.domain.UserPreferences
import eu.anifantakis.commercials.feature.preferences.presentation.PreferencesNavType
import eu.anifantakis.commercials.feature.preferences.presentation.preferencesEntries
import eu.anifantakis.commercials.feature.schedule_email.presentation.screens.send_schedule_email.SendScheduleEmailDialogRoot
import eu.anifantakis.commercials.feature.timetable.presentation.TimetableNavType
import eu.anifantakis.commercials.feature.timetable.presentation.timetableEntries
import eu.anifantakis.commercials.feature.user_management.presentation.UserManagementNavType
import eu.anifantakis.commercials.feature.user_management.presentation.userManagementEntries
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

// One line per FEATURE hierarchy (not per route): the sealed base's CLOSED
// generated serializer covers every route of the feature on all targets -
// adding a route inside a feature changes nothing here.
private val navConfig = navConfigOf(
    navHierarchy<AuthNavType>(),
    navHierarchy<TimetableNavType>(),
    navHierarchy<PreferencesNavType>(),
    navHierarchy<UserManagementNavType>(),
    navHierarchy<MigrationNavType>(),
    navHierarchy<GalaxyBridgeNavType>(),
    navHierarchy<DatabasesNavType>(),
)

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
    // Opens external URLs in the system browser (desktop) / a new tab (web) -
    // Compose's cross-platform handler, no expect/actual needed.
    val uriHandler = LocalUriHandler.current

    // Token persists (no expiry), so a returning user skips the login screen
    val backStack = rememberNavBackStack(
        navConfig,
        if (authSession.isLoggedIn) TimetableNavType.TimetableFlow else AuthNavType.Login
    )
    // Biometric startup gate: evaluated ONCE, at cold start - a remembered
    // session that opted in stays LOCKED behind the platform prompt until it
    // passes. Locking again mid-session would fight the keep-alive.
    var biometricLocked by remember {
        mutableStateOf(authSession.isLoggedIn && authSession.biometricLoginRequired)
    }
    val navigator = remember { Navigator(backStack) }

    // If the session becomes invalid at any point (e.g. the server rejects our
    // token with 401 - the auth layer clears it), bounce back to Login instead
    // of sitting on a screen that can't load data.
    val authRevision by authSession.revision.collectAsState()
    LaunchedEffect(authRevision) {
        if (!authSession.isLoggedIn && navigator.current() != AuthNavType.Login) {
            navigator.resetTo(AuthNavType.Login)
        }
    }

    // App-level dialogs: each belongs to a feature (own Root + ViewModel),
    // but the launch points live on other features' screens, so the app
    // layer renders them.
    // AI assistant COMPANION PANEL: chrome, not a route - it opens BESIDE the
    // current screen (docked at the end edge) so the schedule stays visible
    // and live while chatting. Hosted here so the ViewModel outlives toggling
    // and navigation - the conversation survives both.
    var showAiChat by remember { mutableStateOf(false) }

    var showEmailDialog by remember { mutableStateOf(false) }
    if (showEmailDialog) {
        SendScheduleEmailDialogRoot(onDismiss = { showEmailDialog = false })
    }
    var showChangePassword by remember { mutableStateOf(false) }
    if (showChangePassword) {
        ChangePasswordDialogRoot(onDismiss = { showChangePassword = false })
    }
    var showApiTokens by remember { mutableStateOf(false) }
    if (showApiTokens) {
        ApiTokensDialogRoot(onDismiss = { showApiTokens = false })
    }
    var showAdminMcp by remember { mutableStateOf(false) }
    if (showAdminMcp) {
        AdminMcpDialogRoot(onDismiss = { showAdminMcp = false })
    }
    // Desktop auto-update publishing (super admin): edits what /version serves.
    var showAdminAppUpdate by remember { mutableStateOf(false) }
    if (showAdminAppUpdate) {
        AdminAppUpdateDialogRoot(onDismiss = { showAdminAppUpdate = false })
    }
    // Temp-password login (admin reset / fresh account): trap the user on a
    // mandatory, non-escapable change-password dialog until they set their own.
    // A successful change clears the session and the revision observer above
    // routes to Login; logging in again arrives with the flag cleared.
    val mustChangePassword = remember(authRevision) { authSession.isLoggedIn && authSession.mustChangePassword }
    if (mustChangePassword) {
        ChangePasswordDialogRoot(onDismiss = {}, mandatory = true)
    }

    // Critical-loading back-block (golden-standard AppLoadingIndicator rule):
    // while an uninterruptible operation shows the overlay, swallow back
    // presses here — NavDisplay's onBack is the single system-back entry
    // point on every target, so no per-platform BackHandler is needed.
    val globalStateContainer = koinInject<GlobalStateContainer>()

    // Transition policy, read at composition (the spec lambdas run outside
    // it): platform motion tokens, honouring reduced motion (snap), and
    // mirrored under RTL — "forward" slides from the START edge, so Hebrew
    // navigation no longer animates backwards.
    val motion = AppTheme.visualTokens.motion
    val a11y = AppTheme.a11y
    val forward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1

    // App-chrome commands owned by the navigation root: text zoom (global,
    // maps onto the persisted font-size preference the theme already reads)
    // and the account dialogs it hosts (greyed on the Login screen).
    val commandRegistry = koinInject<CommandRegistry>()
    val prefs = koinInject<UserPreferences>()
    val loggedIn = navigator.backStack.lastOrNull() != AuthNavType.Login
    val navOwner = "NavigationRoot"
    RegisterAppCommand(commandRegistry, navOwner, AppCommand.FONT_LARGER) {
        FontSizePreference.entries.getOrNull(prefs.fontSize.ordinal + 1)?.let { prefs.fontSize = it }
    }
    RegisterAppCommand(commandRegistry, navOwner, AppCommand.FONT_SMALLER) {
        FontSizePreference.entries.getOrNull(prefs.fontSize.ordinal - 1)?.let { prefs.fontSize = it }
    }
    RegisterAppCommand(commandRegistry, navOwner, AppCommand.FONT_RESET) {
        prefs.fontSize = FontSizePreference.MEDIUM
    }
    RegisterAppCommand(commandRegistry, navOwner, AppCommand.CHANGE_PASSWORD, enabled = loggedIn) {
        showChangePassword = true
    }

    ApplicationScaffold { scaffoldPadding ->
        // The AI companion OVERLAYS the content (Box layering) instead of
        // squeezing it: the app below never reflows, and whatever the panel
        // does not cover keeps taking clicks.
        BoxWithConstraints(Modifier.padding(scaffoldPadding)) {
        NavDisplay(
            backStack = navigator.backStack,
            onBack = {
                if (!globalStateContainer.state.value.isCriticalLoading) navigator.goBack()
            },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),   // must be first
                rememberViewModelStoreNavEntryDecorator()
            ),
            transitionSpec = {
                slideInHorizontally(motion.mediumSpec(a11y)) { forward * it } + fadeIn(motion.mediumSpec(a11y)) togetherWith
                        slideOutHorizontally(motion.mediumSpec(a11y)) { -forward * it } + fadeOut(motion.mediumSpec(a11y))
            },
            popTransitionSpec = {
                slideInHorizontally(motion.mediumSpec(a11y)) { -forward * it } + fadeIn(motion.mediumSpec(a11y)) togetherWith
                        slideOutHorizontally(motion.mediumSpec(a11y)) { forward * it } + fadeOut(motion.mediumSpec(a11y))
            },
            entryProvider = entryProvider {

                authEntries(
                    onLoggedIn = { navigator.resetTo(TimetableNavType.TimetableFlow) },
                )

                timetableEntries(
                    onOpenEmailDialog = { showEmailDialog = true },
                    onLogout = {
                        scope.launch {
                            authRepository.logout()   // revokes the token server-side, clears the session
                            navigator.resetTo(AuthNavType.Login)
                        }
                    },
                    onPreferences = { navigator.navigate(PreferencesNavType.Preferences) },
                    onAiChat = { showAiChat = !showAiChat },
                )

                preferencesEntries(
                    navigator = navigator,
                    isAdmin = { authSession.isAdmin },
                    swaggerEnabled = { authSession.swaggerEnabled },
                    aiChatEnabled = { authSession.aiChatProviders.isNotEmpty() },
                    onChangePassword = { showChangePassword = true },
                    onApiTokens = { showApiTokens = true },
                    onAdminMcp = { showAdminMcp = true },
                    onAppUpdate = { showAdminAppUpdate = true },
                    onManageUsers = { navigator.navigate(UserManagementNavType.UserManagement) },
                    onMigration = { navigator.navigate(MigrationNavType.Migration) },
                    onGalaxyBridge = { navigator.navigate(GalaxyBridgeNavType.GalaxyBridge) },
                    onDatabases = { navigator.navigate(DatabasesNavType.Databases) },
                    // Swagger UI on whatever backend this build points at: derive
                    // it from the SAME base URL the API client uses (mirrors the
                    // "/mcp" sibling-URL pattern), so it follows the environment.
                    onOpenSwagger = {
                        uriHandler.openUri(
                            AppConfig.require().serverBaseUrl.trimEnd('/') + "/swagger"
                        )
                    },
                    onAiChat = { showAiChat = true },
                )

                userManagementEntries(
                    navigator = navigator,
                    // the super admin sees every hosted station in their session
                    stationChoices = { authSession.stations.map { it.id to it.name } },
                )

                migrationEntries(navigator)

                galaxyBridgeEntries(navigator)

                databasesEntries(navigator)
            }
        )

        // The companion, as the TOP layer: an in-app slide-over on every
        // platform, plus detach-to-OS-window on desktop (AiChatCompanionHost).
        // Catalog emptied by a server.yaml change or logout hides it even
        // while open.
        val aiAvailable = remember(authRevision) { authSession.isLoggedIn && authSession.aiChatProviders.isNotEmpty() }
        AiChatCompanionHost(
            visible = showAiChat && aiAvailable,
            windowWidth = this@BoxWithConstraints.maxWidth,
            providers = { authSession.aiChatProviders },
            onClose = { showAiChat = false },
        )

        // The biometric lock screen - the TOPMOST layer: nothing underneath
        // is visible or clickable until the prompt passes. Fallback: sign in
        // with the password instead (revokes the remembered session).
        if (biometricLocked) {
            BiometricGate(
                onUnlocked = { biometricLocked = false },
                onUsePassword = {
                    scope.launch {
                        authRepository.logout()
                        biometricLocked = false
                        navigator.resetTo(AuthNavType.Login)
                    }
                },
            )
        }
        }
    }
}


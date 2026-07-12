package eu.anifantakis.commercials.core.presentation.commands

/**
 * The app-level commands a menu bar / keyboard shortcut can invoke. Every
 * command maps onto an INTENT THAT ALREADY EXISTS in some screen or the
 * navigation root - the registry routes, it never implements behaviour.
 */
enum class AppCommand {
    EXPORT_PDF,
    PRINT_REPORT,
    PREVIEW_REPORT,
    SEND_SCHEDULE_EMAIL,
    PREFERENCES,
    CHANGE_PASSWORD,
    RECOVERY_CODES,
    LOGOUT,
    FONT_LARGER,
    FONT_SMALLER,
    FONT_RESET,
}

package eu.anifantakis.commercials.core.presentation.string_resources.lang

import eu.anifantakis.commercials.core.presentation.string_resources.StringKey

/** English translations (also the fallback for any missing key). */
internal val EN_STRINGS: Map<StringKey, String> = mapOf(
    StringKey.ERROR_NO_INTERNET to "No connection to the server",
    StringKey.ERROR_SESSION_EXPIRED to "Your session has expired - please sign in again",
    StringKey.ERROR_FORBIDDEN to "You don't have permission for this action",
    StringKey.ERROR_NOT_FOUND to "Not found on the server",
    StringKey.ERROR_CONFLICT_REFRESH to "The data changed in the meantime - refresh the month",
    StringKey.ERROR_SERIALIZATION to "Unrecognizable server response",
    StringKey.ERROR_SERVER to "Server error ({0})",
    StringKey.ERROR_LOCAL_STORAGE to "Local storage error",

    StringKey.AUTH_INVALID_CREDENTIALS to "Invalid username or password",
    StringKey.AUTH_NO_STATIONS_ASSIGNED to "No stations are assigned to this account",
    StringKey.AUTH_NOT_LOGGED_IN to "Not logged in",
    StringKey.AUTH_NETWORK_UNREACHABLE to "Could not reach the server",

    StringKey.PREFERENCES_LANGUAGE to "Language",
)

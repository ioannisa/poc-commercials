package eu.anifantakis.commercials.core.presentation.string_resources.lang

import eu.anifantakis.commercials.core.presentation.string_resources.StringKey

/** Greek translations. One entry per [StringKey] (completeness is fitness-tested). */
internal val EL_STRINGS: Map<StringKey, String> = mapOf(
    StringKey.ERROR_NO_INTERNET to "Δεν υπάρχει σύνδεση με τον server",
    StringKey.ERROR_SESSION_EXPIRED to "Η συνεδρία έληξε - συνδεθείτε ξανά",
    StringKey.ERROR_FORBIDDEN to "Δεν έχετε δικαίωμα για αυτή την ενέργεια",
    StringKey.ERROR_NOT_FOUND to "Δεν βρέθηκε στον server",
    StringKey.ERROR_CONFLICT_REFRESH to "Τα δεδομένα άλλαξαν στο μεταξύ - ανανεώστε τον μήνα",
    StringKey.ERROR_SERIALIZATION to "Μη αναγνωρίσιμη απάντηση server",
    StringKey.ERROR_SERVER to "Σφάλμα server ({0})",
    StringKey.ERROR_LOCAL_STORAGE to "Σφάλμα τοπικής αποθήκευσης",

    StringKey.AUTH_INVALID_CREDENTIALS to "Λάθος όνομα χρήστη ή κωδικός",
    StringKey.AUTH_NO_STATIONS_ASSIGNED to "Δεν έχουν ανατεθεί σταθμοί σε αυτόν τον λογαριασμό",
    StringKey.AUTH_NOT_LOGGED_IN to "Δεν έχετε συνδεθεί",
    StringKey.AUTH_NETWORK_UNREACHABLE to "Δεν ήταν δυνατή η σύνδεση με τον server",

    StringKey.PREFERENCES_LANGUAGE to "Γλώσσα",
)

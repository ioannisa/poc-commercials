package eu.anifantakis.commercials.core.presentation.util

import eu.anifantakis.commercials.core.domain.util.DataError

/**
 * Operator-facing text for transport/storage failures. Plain String, not
 * StringKey - mono-lingual POC (recorded deviation in GlobalEffect).
 */
fun DataError.toDisplayMessage(): String = when (this) {
    DataError.Network.NO_INTERNET -> "Δεν υπάρχει σύνδεση με τον server"
    DataError.Network.UNAUTHORIZED -> "Η συνεδρία έληξε - συνδεθείτε ξανά"
    DataError.Network.FORBIDDEN -> "Δεν έχετε δικαίωμα για αυτή την ενέργεια"
    DataError.Network.NOT_FOUND -> "Δεν βρέθηκε στον server"
    DataError.Network.CONFLICT -> "Τα δεδομένα άλλαξαν στο μεταξύ - ανανεώστε τον μήνα"
    DataError.Network.SERIALIZATION -> "Μη αναγνωρίσιμη απάντηση server"
    is DataError.Network -> "Σφάλμα server ($name)"
    is DataError.Local -> "Σφάλμα τοπικής αποθήκευσης"
}

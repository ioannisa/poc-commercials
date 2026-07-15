package eu.anifantakis.commercials.mailer

/**
 * Auth emails: the password-reset code and the temp-password notice. Plain,
 * self-contained inline-styled HTML (mail clients ignore <style>/external CSS),
 * in Greek to match the schedule emails. Deliberately minimal - each carries a
 * single credential, so the less chrome the better.
 */

private const val AUTH_EMAIL_HEAD =
    "<!DOCTYPE html><html lang=\"el\"><head><meta charset=\"utf-8\"></head>" +
        "<body style=\"margin:0;padding:24px;background:#F4F5F7;font-family:Arial,Helvetica,sans-serif;color:#1A1A1A;\">"

private const val AUTH_EMAIL_FOOT = "</body></html>"

private fun card(inner: String): String =
    "<div style=\"max-width:440px;margin:0 auto;background:#FFFFFF;border-radius:12px;padding:28px;\">$inner</div>"

private fun codeBox(value: String, big: Boolean): String {
    val size = if (big) "34px;letter-spacing:8px" else "22px;word-break:break-all"
    return "<div style=\"font-family:'Courier New',monospace;font-size:$size;font-weight:bold;" +
        "text-align:center;background:#F0F2F5;border-radius:8px;padding:16px;margin:0 0 16px;\">$value</div>"
}

/** The 6-digit reset code, large and monospaced, plus how long it lasts. */
fun renderPasswordResetEmail(code: String, ttlMinutes: Int): String =
    AUTH_EMAIL_HEAD + card(
        "<h2 style=\"margin:0 0 12px;font-size:20px;\">Επαναφορά κωδικού πρόσβασης</h2>" +
            "<p style=\"margin:0 0 16px;font-size:14px;line-height:1.5;\">" +
            "Ζητήσατε επαναφορά του κωδικού σας. Εισαγάγετε τον παρακάτω κωδικό στην εφαρμογή:</p>" +
            codeBox(code, big = true) +
            "<p style=\"margin:0 0 8px;font-size:13px;color:#555;\">Ισχύει για $ttlMinutes λεπτά και χρησιμοποιείται μία φορά.</p>" +
            "<p style=\"margin:0;font-size:13px;color:#555;\">Αν δεν το ζητήσατε εσείς, αγνοήστε αυτό το μήνυμα - " +
            "ο κωδικός σας δεν άλλαξε.</p>"
    ) + AUTH_EMAIL_FOOT

/**
 * Temp-password notice: for a fresh account ([newAccount] = true) or an admin
 * reset. The password is shown once here; the app forces a change at first login.
 */
fun renderTempPasswordEmail(username: String, tempPassword: String, newAccount: Boolean): String {
    val title = if (newAccount) "Ο λογαριασμός σας δημιουργήθηκε" else "Ο κωδικός σας μηδενίστηκε"
    val lead = if (newAccount) {
        "Δημιουργήθηκε λογαριασμός για εσάς με όνομα χρήστη <b>$username</b>."
    } else {
        "Ο κωδικός του λογαριασμού <b>$username</b> μηδενίστηκε από διαχειριστή."
    }
    return AUTH_EMAIL_HEAD + card(
        "<h2 style=\"margin:0 0 12px;font-size:20px;\">$title</h2>" +
            "<p style=\"margin:0 0 16px;font-size:14px;line-height:1.5;\">$lead Συνδεθείτε με τον προσωρινό κωδικό:</p>" +
            codeBox(tempPassword, big = false) +
            "<p style=\"margin:0;font-size:13px;color:#555;\">Με την πρώτη σύνδεση θα σας ζητηθεί να ορίσετε δικό σας κωδικό.</p>"
    ) + AUTH_EMAIL_FOOT
}
